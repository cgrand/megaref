# megaref

## Installation

```clj
[net.cgrand/megaref "0.3.0"]
```

## Description

This library introduces two new reference types (associative refs and subrefs) which participate in STM transactions. 

(See http://clj-me.cgrand.net/2011/10/06/a-world-in-a-ref/ for the core
principle.)

These new ref types allow to deal with the granularity problem even in an 
existing codebase without changing the shape of your code.

An associative ref (aka "megaref") is a ref designed to allow concurrent path-keyed updates. As a consequence, it can store bigger values than a ref.

A subref provides a scoped view on another associative ref. Its main use case is to replace regular refs in existing codebases to ease migration to megarefs.

Alongside those new ref types, this library introduces `alter-in`, `commute-in`, 
`deref-in`, `ref-set-in` and `ensure-in` which carry the same STM semantics as 
respectively `alter`, `commute`, `deref`, `ref-set` and `ensure but` at the path
level rather than at the whole ref level. Thus they allow for more concurrency
(eg `(alter-in r path f ...)` vs `(alter r assoc-in path f ...)`).

Two `alter-in`s on different paths (eg `[:a :b :c]` and `[:a :b :d]`) on
the same ref won't conflict. However `[:a :b]` and `[:a :b :c] will conflict;
this check can be turned off by setting the option `:check-prefixes` to `false`. Obviously, you should only turn it off when you never update paths that are prefixes of each other.

The `:guards-count` option is the number of "stripes" used by the megaref, so higher values provide higher throughput (until it plateaues). The number to set depends on several factors: whether the app is very concurrent or not, the average number of paths modified by a transaction, the average path length and the value of the `:check-prefixes` value. 

At runtime, options (including the validator fn and history settings) can be
queried and set using an uniform interface: `get-options` and `set-option!`.

This library also provides drop in replacements for alter, commute, ref-set and
ensure. These replacements work either on refs or on the new ref types.

## Usage

```clj
(require '[net.cgrand.megaref :as mega])

(let [mr (mega/ref (vec (repeat 10 (vec (repeat 10 0)))))
      r (ref @mr)
      paths (take 100000 (repeatedly (fn []
                                            [(rand-int 10) (rand-int 10)])))
      patha (vec (take-nth 2 paths))
      pathb (vec (take-nth 2 (next paths)))
      bench
      (fn [r]
        (let [p (promise)
              a (future 
                  @p
                  (doseq [path patha]
                    (dosync
                      (dotimes [_ 1e5])
                      (mega/alter-in r path inc))))
              b (future 
                  @p
                  (doseq [path pathb]
                    (dosync
                      (dotimes [_ 1e5])
                      (mega/alter-in r path inc))))]
          (time 
            (do
              (deliver p :go!)
              @a 
              @b))))]
  
  (println "With refs:")
  (bench r)
  (println "With megarefs:")
  (bench mr)
  (println "Are the results equal?"
    (= @mr @r)))
```

Output:

```
With refs:
"Elapsed time: 3864.564 msecs"
With megarefs:
"Elapsed time: 2170.374 msecs"
Are the results equal? true
```

## Converting an existing code base to megarefs

The idea is to aggregate several refs in one megaref and to replace those
refs by subrefs on the newly minted megaref (and to replace all calls to 
alter/commute/... by their homonymous replacements).

See https://github.com/cgrand/megaref/commit/c9d8f3932d8da92172f2b8c1901f2cdaa716f784 
and https://github.com/cgrand/megaref/commit/d46f08f26b902dec8d26cb86aae73d7763144905
for the conversion of the original STM ants demo by Rich Hickey to megarefs.

## License

Copyright © 2012-2013 Christophe Grand

Distributed under the Eclipse Public License, the same as Clojure.
