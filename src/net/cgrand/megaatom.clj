(ns net.cgrand.megaatom)

(defprotocol AssociativeAtom
  (compare-and-set-in! [a path before after])
  (reset-in! [a path v])
  (-swap-in! [a path f args]))

(defn- cas* [x p before after]
  (let [ok (identical? x before)]
    (deliver p ok)
    (if ok after x)))

(defn swap-in! [a path f & args])

(extend-protocol AssociativeAtom
  clojure.lang.Atom
  (compare-and-set-in! [a path before after]
    (let [p (promise)]
      (swap! a update-in path cas* p before after)
      @p))
  (reset-in! [a path v]
    (swap! a assoc-in path v))
  (-swap-in! [a path f args]
    (swap! a update-in path (memoize #(apply f % args)))))

(defn subatom [a path]
  (let [path (vec path)]
    (reify
      clojure.lang.IDeref
      (deref [_]
        (get-in @a path))
      AssociativeAtom
      (compare-and-set-in! [a p before after]
        (compare-and-set-in! a (into path p) before after))
      (reset-in! [a p v]
        (reset-in! a (into path p) v))
      (-swap-in! [_ p f args]
        (swap! a update-in (into path p)
          (memoize #(apply f % args)))))))

(defn swap-in! [a path f & args]
  (-swap-in! a f args))