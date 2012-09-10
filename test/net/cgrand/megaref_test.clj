(ns net.cgrand.megaref-test
  (:use [clojure.test :only [deftest is are run-tests]]
        [net.cgrand.megaref]))

(def matrix (vec (map vec (partition 10 (range 100)))))

(deftest test-deref
  (is (= matrix @(megaref matrix)))
  (is (= (get-in matrix [1]) @(subref (megaref matrix) [1])))
  (is (= (get-in matrix [1]) @(subref (ref matrix) [1]))))

(deftest test-deref-in
  (is (= matrix (deref-in (megaref matrix) [])))
  (is (= (get-in matrix [1]) (deref-in (megaref matrix) [1])))
  (is (= (get-in matrix [1]) (deref-in (ref matrix) [1])))
  (is (= (get-in matrix [1 0]) (deref-in (subref (megaref matrix) [1]) [0]))))

(deftest test-alter-in
  (is (= (rseq matrix) (dosync (alter-in (megaref matrix) [] (comp vec rseq)))))
  (is (= (concat (get-in matrix [1]) "ABC") (dosync (alter-in (megaref matrix) [1] concat "ABC"))))
  (is (= (concat (get-in matrix [1]) "ABC") (dosync (alter-in (ref matrix) [1] concat "ABC"))))
  (is (= (+ (get-in matrix [1 0]) 3) (dosync (alter-in (subref (megaref matrix) [1]) [0]
                                                       + 3)))))

(deftest test-commute-in
  (is (= (rseq matrix) (dosync (commute-in (megaref matrix) [] (comp vec rseq)))))
  (is (= (concat (get-in matrix [1]) "ABC") (dosync (commute-in (megaref matrix) [1] concat "ABC"))))
  (is (= (concat (get-in matrix [1]) "ABC") (dosync (commute-in (ref matrix) [1] concat "ABC"))))
  (is (= (+ (get-in matrix [1 0]) 3) (dosync (commute-in (subref (megaref matrix) [1]) [0]
                                                       + 3)))))