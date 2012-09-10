(ns net.cgrand.megaref
  "STM ref types specialized for associative data.
   Those megarefs allow for more concurrency than a single ref while still
   being cheap to snapshot (compared to a lot of hot refs)."
  (:refer-clojure :exclude [commute alter ref-set ensure])
  (:require [clojure.core :as core]))

;; except ensure-in, ref-set-in and alter-in, the others are here because
;; they can be optimized in a mega ref wth several roots
(defprotocol AssociativeRef
  (-alter-in [aref ks f args]
    "Protocol method backing alter-in. Must return the newly set
     in-transaction-value of aref")
  (-commute-in [aref ks f args]
    "Protocol method backing commute-in. Must return the newly set
     in-transaction-value of aref")
  (ref-set-in [aref ks v]
    "Equivalent to (alter aref assoc-in ks v) but allows for more concurrency
     and returns v.")
  (ensure-in [aref ks]
    "Must be called in a transaction. Protects a part of the ref from 
    modification by other transactions.  Returns the in-transaction-value of
    this part of the ref (that is (deref-in aref ks)).")
  (deref-in [aref ks]
    "Equivalent to (get-in @aref ks)."))

(defprotocol Tuneable
  (set-option! [this option value])
  (get-options [this]))

(extend-type clojure.lang.Ref
  AssociativeRef
  (-alter-in [ref ks f args]
    (if (seq ks)
      (get-in (core/alter ref update-in ks #(apply f % args)) ks)
      (apply core/alter ref f args)))
  (-commute-in [ref ks f args]
    (if (seq ks)
      (get-in (core/commute ref update-in ks #(apply f % args)) ks)
      (apply core/commute ref f args)))
  (ref-set-in [ref ks v]
    (if (seq ks)
      (core/alter ref assoc-in ks v)
      (core/ref-set ref v))
    v)
  (ensure-in [ref ks] (core/ensure ref))
  (deref-in [ref ks]
    (get-in @ref ks))
  Tuneable
  (set-option! [this option value]
    (io!
      (case option
        :validator (set-validator! this value)
        :max-history (ref-max-history this value)
        :min-history (ref-min-history this value)
        (throw (IllegalArgumentException. (str "Unknown option: " option))))))
  (get-options [this] {:validator (get-validator this)
                       :max-history (ref-max-history this)
                       :min-history (ref-min-history this)}))

(defn alter-in 
  "Must be called in a transaction. Sets the in-transaction-value of aref to:

  (apply update-in in-transaction-value-of-ref ks f args)

  and returns the in-transaction-value of the altered part (not the whole
  aref value).

  At the commit point of the transaction, sets the value of ref to be:

  (assoc-in most-recently-committed-value-of-ref ks 
    (get-in in-transaction-value-of-ref ks)) 

  while maintaining the guarantee that concurrent transactions didn't change
  (get-in value-of-ref ks).
  Thus alter-in offers the alter semantics at the path level rather than at the
  ref level.
  Two concurrent alter-in can commute when their paths are not prefix from one
  another." 
  [aref ks f & args]
  (-alter-in aref ks f args))

(defn commute-in 
  "Must be called in a transaction. Sets the in-transaction-value of aref to:

  (apply update-in in-transaction-value-of-ref ks f args)

  and returns the in-transaction-value of the commuted part (not the whole
  aref value). 

  At the commit point of the transaction, sets the value of ref to be:

  (apply update-in most-recently-committed-value-of-ref ks f args)

  Thus f should be commutative, or, failing that, you must accept
  last-one-in-wins behavior.  commute-in allows for more concurrency than
  alter-in." 
  [aref ks f & args]
  (-commute-in aref ks f args))

(defn alter 
  "Same as clojure.core/alter but works on AssociativeRefs too (by assuming
   an empty path)."
  [r f & args]
  (-alter-in r nil f args))

(defn commute 
  "Same as clojure.core/commute but works on AssociativeRefs too (by assuming
   an empty path)."
  [r f & args]
  (-commute-in r nil f args))

(defn ref-set 
  "Same as clojure.core/ref-set but works on AssociativeRefs too (by assuming
   an empty path)."
  [r v]
  (ref-set-in r nil v))

(defn ensure 
  "Same as clojure.core/ensure but works on AssociativeRefs too (by assuming
   an empty path)."
  [r]
  (ensure-in r nil))

(defn- ensure-path 
  "Ensure that prefix paths are not updated. path must be a vector."
  [guard-ref-for path]
  (loop [path path]
    (when-not (= [] path)
      (ensure (guard-ref-for path))
      (recur (pop path)))))

(defn- guards-fn [n]
  (let [guards (vec (repeatedly n #(ref nil)))]
    (fn 
      ([] guards)
      ([x] (nth guards (mod (hash x) n))))))

(defn- invalidate [guard]
  (ref-set guard nil))

(defn- megaref-validator [validator]
  (if validator 
    #(and (or (associative? %) (nil? %)) (validator %))
    #(or (associative? %) (nil? %))))

(deftype SingleRootRef [r guard ^:unsynchronized-mutable options
                        ^:volatile-mutable ^boolean guard-prefixes]
  AssociativeRef
  (-alter-in [this ks f args]
    (if (seq ks)
      (let [guard (ensure guard)]
        (when guard-prefixes (ensure-path guard (pop (vec ks))))
        (invalidate (guard ks))
        (let [v (apply f (get-in @r ks) args)]
          ; v is precomputed to not evaluate it twice because of commute
          (commute r assoc-in ks v)
          v))
      (do
        (doseq [r ((ensure guard))] (ensure r))
        (apply alter r f args))))
  (-commute-in [this ks f args]
    (if (seq ks)
      (get-in (apply commute r update-in ks f args) ks)
      (apply commute r f args)))
  (ref-set-in [this ks v]
    (-alter-in this ks (constantly v) nil))
  (ensure-in [this ks]
    (if (seq ks)
      (let [guard (ensure guard)]
        (if guard-prefixes
          (ensure-path guard (vec ks))
          (ensure (guard ks))))
      (ensure r))
    (deref-in this ks))
  (deref-in [this ks]
    (get-in @r ks))
  Tuneable
  (set-option! [this option value]
    (io!
      (locking this
        (case option
          :guards-count (dosync (ref-set guard (guards-fn value)))
          :validator (set-validator! r (megaref-validator value))
          :max-history (ref-max-history r value)
          :min-history (ref-min-history r value)
          :guard-prefix (set! guard-prefixes (boolean value))
          (throw (IllegalArgumentException. (str "Unknown option: " option))))
        (set! options (assoc options option value)))))
  (get-options [this] (locking this options))
  clojure.lang.IDeref
  (deref [this]
    @r)
  clojure.lang.IRef
  (setValidator [this vf]
    (set-option! this :validator vf))
  (getValidator [this]
    (-> this get-options :validator))
  (getWatches [this]
    (.getWatches ^clojure.lang.IRef r))
  (addWatch [this key callback]
    (.addWatch ^clojure.lang.IRef r key callback))
  (removeWatch [this key]
    (.removeWatch ^clojure.lang.IRef r key)))

(defn megaref
  "Creates and returns an associative ref with an initial value of x and zero or
  more options (in any order):

  :validator validate-fn

  :min-history (default 0)
  :max-history (default 10)
  :guards-count (default to 32)
  :guard-prefixes (default to true)

  Options can be queryed by #'get-options and changed with #'set-option!"
  [x & {:as options}]
  (let [options (merge {:validator nil, :guards-count 32,
                        :min-history 0, :max-history 10,
                        :guard-prefixes true}
                  (select-keys options [:validator :min-history :max-history]))
        root-options (update-in options [:validator] megaref-validator)]
    (SingleRootRef. (apply ref x (mapcat seq root-options))
                    (ref (guards-fn (:guards-count options)))
                    options
                    (boolean (:guard-prefixes options)))))

(deftype SubRef [pref pks]
  AssociativeRef
  (-alter-in [ref ks f args]
    (-alter-in pref (concat pks ks) f args))
  (-commute-in [ref ks f args]
    (-commute-in pref (concat pks ks) f args))
  (ref-set-in [ref ks v]
    (ref-set-in pref (concat pks ks) v))
  (deref-in [ref ks]
    (deref-in pref (concat pks ks)))
  clojure.lang.IDeref
  (deref [this]
    (deref-in pref pks)))

(defn subref [ref ks]
  "Creates and returns an associative ref which only exposes a part of the
   parent ref. The subref is backed by the parent ref, it's only a limited
   view of the same identity."
  (if (seq ks) (SubRef. ref ks) ref))