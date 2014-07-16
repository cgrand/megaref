(ns net.cgrand.megaagent)

(defmacro implies [a b]
  `(or (not ~a) ~b))

(defn do-send-in [root send path f args]
  (let [guards (-> root meta ::guards)
        guard-for #(nth guards (mod (hash %) (count guards)))
        release-parent-guard
        (fn release-parent-guard [path]
          (when (seq path)
            (let [ppath (pop path)]
              (send (guard-for ppath)
                (fn [[n q]]
                  (release-parent-guard ppath)
                  (if (= 1 n)
                    (reduce #(%2 %1) [0 []] q)
                    [(dec n) q]))))))
        propagate
        (fn propagate [prefix remainder]
          (if-let [[seg & segs] (seq remainder)]
            ; prefix
            (send (guard-for prefix)
              (fn self [[n q]]
                (if (seq q)
                  [n (conj q self)]
                  (do
                    (propagate (conj prefix seg) segs)
                    [(inc n) q]))))
            ; whole path
            (send (guard-for prefix)
              (fn self [[n q :as state]]
                #_{:pre [(implies (seq q) (pos? n))]}
                (if (pos? n)
                  ; inner ongoing update, must wait
                  [n (conj q self)]
                  ; all clear
                  (do
                    (swap! root assoc-in path 
                      (apply f (get-in @root path) args))
                    (release-parent-guard prefix)
                    state))))))]
    (propagate [] path))
  root)

(defn send-in [a path f & args]
  (do-send-in a send path f args))

(defn send-off-in [a path f & args]
  (do-send-in a send-off path f args))

(defn send-via-in [executor a path f & args]
  (do-send-in a (partial send-via executor) path f args))

(defn megaagent [value & {:keys [guards-count] :or {guards-count 32}}]
  (doto (atom value)
    (alter-meta! assoc ::guards (repeatedly guards-count #(agent [0 []])))))

#_(do
   (def ma (megaagent {}))
   (send-in ma [:a :b] (fn [_]
                         (Thread/sleep 10000)
                         {:t (java.lang.System/currentTimeMillis)}))
   (send-in ma [:c :d] (fn [_]
                         (Thread/sleep 2000)
                         (java.lang.System/currentTimeMillis)))
   (send-in ma [:a :b :c] (fn [_]
                            (Thread/sleep 5000)
                           (java.lang.System/currentTimeMillis)))
   (send-in ma [:a] (fn [m]
                     (assoc m
                       :t
                       (java.lang.System/currentTimeMillis))))
   (dotimes [_ 20]
     (Thread/sleep 1000)
     (prn @ma)))