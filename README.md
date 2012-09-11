# megaref

This library introduces two new reference types which participate in STM
transactions. 

(See http://clj-me.cgrand.net/2011/10/06/a-world-in-a-ref/ for the core
principle.)

These new ref types allow to deal with the granularity problem even in an 
existing codebase without changing the shape of your code.

Alongside those new ref types, this library introduces alter-in, commute-in, 
deref-in, ref-set-in and ensure-in which carry the same STM semantics as 
alter, commute, deref, ref-set and ensure but at the path level rather than at
the whole ref level. Thus they allow for more concurrency.

For example two alter-in on different paths (eg [:a :b :c] and [:a :b :d]) on
the same ref won't conflict. However [:a :b] and [:a :b :c] will conflict;
this check can be turned off by setting the option :guard-prefixes to false.

The amount of concurrency can be controlled by setting the :guards-count option.

At runtime, options (including the validator fn and history seetings) can be
queried and set using an uniform interface: get-options and set-options.

This library also provides drop in replacements for alter, commute, ref-set and
ensure. These replacements work either on refs or on the new ref types.

## Usage

    (let [mr (megaref (vec (repeat 10 (vec (repeat 10 0)))))
          paths (take 100000 (repeatedly (fn []
                                                [(rand-int 10) (rand-int 10)])))
          patha (vec (take-nth 2 paths))
          pathb (vec (take-nth 2 (next paths)))
          p (promise)
          a (future 
              @p
              (doseq [path patha]
                (dosync
                  (dotimes [_ 1e5])
                  (alter-in mr path inc))))
          b (future 
              @p
              (doseq [path pathb]
                (dosync
                  (dotimes [_ 1e5])
                  (alter-in mr path inc))))]
      
      (time 
        (do
          (deliver p :go!)
          @a @b)))

## Converting an existing code base to megarefs

The idea is to aggregate several refs in one megaref and to replace those
refs by subrefs on the newly minted megaref (and to replace all calls to 
alter/commute/... by their homonymous replacements).

## License

Copyright © 2012 Christophe Grand

Distributed under the Eclipse Public License, the same as Clojure.
