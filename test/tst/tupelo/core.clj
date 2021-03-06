;   Copyright (c) Alan Thompson. All rights reserved. 
;   The use and distribution terms for this software are covered by the Eclipse Public License 1.0
;   (http://opensource.org/licenses/eclipse-1.0.php) which can be found in the file epl-v10.html at
;   the root of this distribution.  By using this software in any fashion, you are agreeing to be
;   bound by the terms of this license.  You must not remove this notice, or any other, from this
;   software.
(ns tst.tupelo.core
  (:require [clojure.string                         :as str]
            [clojure.test.check                     :as tc]
            [clojure.test.check.generators          :as gen]
            [clojure.test.check.properties          :as prop]
            [clojure.test.check.clojure-test        :as tst]
            [tupelo.misc                            :as tm] )
  (:use tupelo.core 
        clojure.test ))

(spyx *clojure-version*)

(deftest truthy-falsey-tst
  (let [data [true :a 'my-symbol 1 "hello" \x false nil] ]
    (testing "basic usage"
      (let [truthies    (filter boolean data)       ; coerce to primitive type
            falsies     (filter not     data) ]     ; unnatural syntax
        (is (and  (= truthies [true :a 'my-symbol 1 "hello" \x] )
                  (= falsies  [false nil] ) )))
      (let [truthies    (filter truthy? data)
            falsies     (filter falsey? data) ]
        (is (and  (= truthies [true :a 'my-symbol 1 "hello" \x] )
                  (= falsies  [false nil] ) ))))

    (testing "improved usage"
      (let [count-if (comp count filter) ]
        (let [num-true    (count-if boolean data)   ; awkward phrasing
              num-false   (count-if not     data) ] ; doesn't feel natural
          (is (and  (= 6 num-true) 
                    (= 2 num-false) )))
        (let [num-true    (count-if truthy? data)   ; matches intent much better
              num-false   (count-if falsey? data) ]
          (is (and  (= 6 num-true)
                    (= 2 num-false) )))))
  ))

(deftest any-tst
  (testing "basic usage"
    (is (= true   (any? odd? [1 2 3] ) ))
    (is (= false  (any? odd? [2 4 6] ) ))
    (is (= false  (any? odd? []      ) )) ))

(deftest not-empty-tst
  (testing "basic usage"
    (is (every?     not-empty? ["1" [1] '(1) {:1 1} #{1}    ] ))
    (is (not-any?   not-empty? [""  []  '()  {}     #{}  nil] ))

    (is (= (map not-empty? ["1" [1] '(1) {:1 1} #{1} ] )
           [true true true true true]  ))
    (is (= (map not-empty? ["" [] '() {} #{} nil] )
           [false false false false false false ] ))))

(deftest conjv-tst
  (testing "basic usage"
    (is (= [  2  ]  (conjv  []  2   )))
    (is (= [  2  ]  (conjv '()  2   )))
    (is (= [  2 3]  (conjv  []  2  3)))
    (is (= [  2 3]  (conjv '()  2  3)))

    (is (= [1 2 3]  (conjv  [1] 2  3)))
    (is (= [1 2 3]  (conjv '(1) 2  3)))
    (is (= [1 2 3]  (conjv  [1  2] 3)))
    (is (= [1 2 3]  (conjv '(1  2) 3)))

    (is (= [1 2 3 4]  (conjv  [1  2] 3 4)))
    (is (= [1 2 3 4]  (conjv '(1  2) 3 4)))
    (is (= [1 2 3 4]  (conjv  [1] 2  3 4)))
    (is (= [1 2 3 4]  (conjv '(1) 2  3 4))) )

  (testing "vector elements"
    (is (=    [[1 2] [3 4]  [5 6] ]
      (conjv '([1 2] [3 4]) [5 6] ) )))

  (testing "lazy seqs/apply"
    (is (= [0 1 2 3 4 5] (conjv (range 4) 4 5)))
    (is (= [0 1 2 3 4 5] (apply conjv [0] (range 1 6)))) ))

(deftest strcat-tst
  (is (= "a" (strcat \a  )))    (is (= "a" (strcat [\a]  )))
  (is (= "a" (strcat "a" )))    (is (= "a" (strcat ["a"] )))
  (is (= "a" (strcat 97  )))    (is (= "a" (strcat [97]  )))

  (is (= "ab" (strcat \a   \b   ))) (is (= "ab" (strcat [\a]  \b   )))
  (is (= "ab" (strcat \a  [\b]  ))) (is (= "ab" (strcat [\a   \b]  )))
  (is (= "ab" (strcat "a"  "b"  ))) (is (= "ab" (strcat ["a"] "b"  )))
  (is (= "ab" (strcat "a" ["b"] ))) (is (= "ab" (strcat ["a"  "b"] )))
  (is (= "ab" (strcat 97   98   ))) (is (= "ab" (strcat [97]  98   )))
  (is (= "ab" (strcat 97  [98]  ))) (is (= "ab" (strcat [97   98]  )))

  (is (= "abcd" (strcat              97  98   "cd" )))
  (is (= "abcd" (strcat             [97  98]  "cd" )))
  (is (= "abcd" (strcat (byte-array [97  98]) "cd" )))

  (let [chars-set   (into #{} (tm/char-seq \a \z)) 
        str-val     (strcat chars-set) ]
    (is (= 26 (count chars-set)))
    (is (= 26 (count str-val)))
    (is (= 26 (count (re-seq #"[a-z]" str-val))))))

(deftest seqable-tst
  (is (seqable? "abc"))
  (is (seqable?  {1 2 3 4}))
  (is (seqable? #{1 2 3}))
  (is (seqable? '(1 2 3)))
  (is (seqable?  [1 2 3]))
  (is (seqable? (byte-array [1 2])))

  (is (not (seqable?  1 )))
  (is (not (seqable? \a ))))

(deftest keyvals-t
  (testing "basic usage"
    (let [m1 {:a 1 :b 2 :c 3} 
          m2 {:a 1 :b 2 :c [3 4]} ]
      (is (= m1 (apply hash-map (keyvals m1))))
      (is (= m2 (apply hash-map (keyvals m2)))) 
    )))
; AWTAWT TODO: add test.check

(deftest with-exception-default-t
  (testing "basic usage"
    (is (thrown?    Exception                       (/ 1 0)))
    (is (= nil      (with-exception-default nil     (/ 1 0))))
    (is (= :dummy   (with-exception-default :dummy  (/ 1 0))))
    (is (= 123      (with-exception-default 0       (Long/parseLong "123"))))
    (is (= 0        (with-exception-default 0       (Long/parseLong "12xy3"))))
    ))

(deftest forv-t
  (is (= (forv [x (range 23)] (* x x))
         (for  [x (range 23)] (* x x))))
  (is (= (forv [x (range 5)  y (range 2 9)] (str x y))
         (for  [x (range 5)  y (range 2 9)] (str x y)))))

(deftest spy-t
  (testing "basic usage"
    (let [side-effect-cum-sum (atom 0)  ; side-effect running total

          ; Returns the sum of its arguments AND keep a running total.
          side-effect-add!  (fn [ & args ]
                              (let [result (apply + args) ]
                                (swap! side-effect-cum-sum + result)
                                result))
    ]
      (is (= "hi => 5" 
          (str/trim (with-out-str (spy (side-effect-add! 2 3) :msg "hi"))) ))
      (is (= "hi => 5" 
          (str/trim (with-out-str (spy :msg "hi"  (side-effect-add! 2 3)))) ))
      (is (= "(side-effect-add! 2 3) => 5" 
          (str/trim (with-out-str (spyx (side-effect-add! 2 3)))) ))
      (is (= 15 @side-effect-cum-sum)))

    (is (= "first => 5\nsecond => 25"
        (str/trim (with-out-str (-> 2 
                                    (+ 3) 
                                    (spy :msg "first" )
                                    (* 5)
                                    (spy :msg "second") )))))
    (is (= "first => 5\nsecond => 25"
        (str/trim (with-out-str (->> 2 
                                    (+ 3) 
                                    (spy :msg "first" )
                                    (* 5)
                                    (spy :msg "second") )))))

    (let [side-effect-cum-sum (atom 0)  ; side-effect running total

          ; Returns the sum of its arguments AND keep a running total.
          side-effect-add!  (fn [ & args ]
                              (let [result (apply + args) ]
                                (swap! side-effect-cum-sum + result)
                                result))
    ]
      (is (= "value => 5" 
          (str/trim (with-out-str (spy (side-effect-add! 2 3) :msg "value")))))
      (is (= "value => 5" 
          (str/trim (with-out-str (spy :msg "value"  (side-effect-add! 2 3))))))
      (is (= 10 @side-effect-cum-sum))

      (is (= "value => 5" (str/trim (with-out-str (spy "value" (+ 2 3) )))))
      (is (=   "spy => 5" (str/trim (with-out-str (spy         (+ 2 3) )))))

      (is (= "(str \"abc\" \"def\") => \"abcdef\"" 
          (str/trim (with-out-str (spyx (str "abc" "def") )))))

      (is (thrown? IllegalArgumentException  (spy "some-msg" 42 :msg)))
    )))

(deftest spyxx-t
  (let [val1  (into (sorted-map) {:a 1 :b 2})
        val2  (+ 2 3) ]
    (is (= "val1 => clojure.lang.PersistentTreeMap->{:a 1, :b 2}"
        (str/trim (with-out-str (spyxx val1 )))  ))

    (is (= "val2 => java.lang.Long->5"
        (str/trim (with-out-str (spyxx val2 ))) ))
  ))

(deftest t-safe->
  (is (= 7 (safe-> 3 (* 2) (+ 1))))
  (let [mm  {:a {:b 2}}]
    (is (= (safe-> mm :a)     {:b 2} ))
    (is (= (safe-> mm :a :b)      2))
    (is (thrown? IllegalArgumentException   (safe-> mm :x)))
    (is (thrown? IllegalArgumentException   (safe-> mm :a :x)))
    (is (thrown? IllegalArgumentException   (safe-> mm :a :b :x)))
  ))

(deftest t-it->
  (is (= 2  (it-> 1
                  (inc it)
                  (+ 3 it)
                  (/ 10 it))))
  (let [mm  {:a {:b 2}}]
    (is (= (it-> mm (:a it)          )  {:b 2} ))
    (is (= (it-> mm (it :a)  (:b it) )      2  ))))
  

(deftest t-rel=
  (is (rel= 1 1 :digits 4 ))
  (is (rel= 1 1 :tol    0.01 ))

  (is (thrown? IllegalArgumentException  (rel= 1 1 )))
  (is (thrown? IllegalArgumentException  (rel= 1 1 4)))
  (is (thrown? IllegalArgumentException  (rel= 1 1 :xxdigits 4      )))
  (is (thrown? IllegalArgumentException  (rel= 1 1 :digits   4.1    )))
  (is (thrown? IllegalArgumentException  (rel= 1 1 :digits   0      )))
  (is (thrown? IllegalArgumentException  (rel= 1 1 :digits  -4      )))

  (is (thrown? IllegalArgumentException  (rel= 1 1 :tol    -0.01    )))
  (is (thrown? IllegalArgumentException  (rel= 1 1 :tol     "xx"    )))
  (is (thrown? IllegalArgumentException  (rel= 1 1 :xxtol   0.01    )))

  (is      (rel= 0 0 :digits 3 ))

  (is      (rel= 1 1.001 :digits 3 ))
  (is (not (rel= 1 1.001 :digits 4 )))
  (is      (rel= 123450000 123456789 :digits 4 ))
  (is (not (rel= 123450000 123456789 :digits 6 )))

  (is      (rel= 1 1.001 :tol 0.01 ))
  (is (not (rel= 1 1.001 :tol 0.0001 )))
)

(comment    ; example usage w/o -> macro
  (def cust->zip
    "A map from (int) customer-id to (string-5) zipcode, like { 96307657 \"54665\", ...}"
    (spy-last "#00 map:"
      (into (sorted-map) 
        (spy-last "#01 for:"
          (for [cust-zip-map cust-zips]
            (spy-last "#02 vec:" 
              [ (:customer-id  cust-zip-map)
                (:zipcode      cust-zip-map)  ] ))))))
)

(deftest t-glue
  (is (= (glue [1 2] [3 4] [5 6])        [1 2 3 4 5 6]))
  (is (= (glue {:a 1} {:b 2} {:c 3})     {:a 1 :c 3 :b 2}))
  (is (= (glue #{1 2} #{3 4} #{6 5})     #{1 2 6 5 3 4}))

  (is (= (glue (sorted-map) {:a 1} {:b 2} {:c 3})   {:a 1 :b 2 :c 3} ))
  (is (= (glue (sorted-set) #{1 2} #{3 4} #{6 5})   #{1 2 3 4 5 6}))

  (is (= (glue (sorted-map) {:a 1 :b 2} {:c 3 :d 4} {:e 5 :f 6})   
                            {:a 1 :b 2   :c 3 :d 4   :e 5 :f 6} ))
  (is (= (seq (glue (sorted-map) {:a 1   :b 2} {:c 3   :d 4   :e 5} {:f 6}))
                               [ [:a 1] [:b 2] [:c 3] [:d 4] [:e 5] [:f 6] ] ))
)

(deftest t-match
  (testing "vectors"
    (let [vv [1 2  3]
          tt [1 2  3]
          ww [1 :* 3]
          zz [1 2  4] ]
      (is (wild-match? vv tt))
      (is (not (wild-match? vv zz))))
    (let [vv [1  [2 3]]
          tt [1  [2 3]]
          ww [:* [2 3]]
          zz [9  [2 3]] ]
      (is (wild-match? vv tt))
      (is (wild-match? vv ww))
      (is not (wild-match? vv zz)))
  )
  (testing "maps"
    (let [vv {:a 1 }
          tt {:a 1 }
;         w1 {:* 1 }  ; #todo can't match keys now
          w2 {:a :*}
          zz {:a 2 }
    ]
      (is (wild-match? vv tt))
;     (is (wild-match? vv w1)) ; #todo
      (is (wild-match? vv w2))
      (is (not (wild-match? vv zz)))
    )
    (let [vv {:a 1 :b {:c 3}}
          tt {:a 1 :b {:c 3}}
;         w1 {:* 1 :b {:c 3}}  ; #todo
          w2 {:a :* :b {:c 3}}
;         w3 {:a 1 :* {:c 3}}  ; #todo
;         w4 {:a 1 :b {:* 3}}  ; #todo
          w5 {:a 1 :b {:c :*}}
          zz {:a 2 :b {:c 3}}
    ]
      (is (wild-match? vv tt))
;     (is (wild-match? vv w1)) ; #todo
      (is (wild-match? vv w2))
;     (is (wild-match? vv w3)) ; #todo
;     (is (wild-match? vv w4))
      (is (wild-match? vv w5))
      (is (not (wild-match? vv zz)))
    )
  )
  (testing "vecs & maps 1"
    (let [vv [:a 1  :b {:c  3} ]
          tt [:a 1  :b {:c  3} ]
          w1 [:* 1  :b {:c  3} ]
          w2 [:a :* :b {:c  3} ]
          w3 [:a 1  :* {:c  3} ]
;         w4 [:a 1  :b {:*  3} ]
          w5 [:a 1  :b {:c :*} ]
          zz [:a 2  :b {:c  3} ]
    ]
      (is (wild-match? vv tt))
      (is (wild-match? vv w1))
      (is (wild-match? vv w2))
      (is (wild-match? vv w3))
;     (is (wild-match? vv w4))
      (is (wild-match? vv w5))
      (is (not (wild-match? vv zz)))
    )
  )
  (testing "vecs & maps 2"
    (let [vv {:a 1  :b [:c  3] }
          tt {:a 1  :b [:c  3] }
;         w1 {:* 1  :b [:c  3] }
          w2 {:a :* :b [:c  3] }
;         w3 {:a 1  :* [:c  3] }
          w4 {:a 1  :b [:*  3] }
          w5 {:a 1  :b [:c :*] }
          z1 {:a 2  :b [:c  3] }
          z2 {:a 1  :b [:c  9] }
    ]
      (is (wild-match? vv tt))
;     (is (wild-match? vv w1))
      (is (wild-match? vv w2))
;     (is (wild-match? vv w3))
      (is (wild-match? vv w4))
      (is (wild-match? vv w5))
      (is (not (wild-match? vv z1)))
      (is (not (wild-match? vv z2)))
    )
  )
)

(deftest t-clip-str
  (testing "single string"
    (is (= ""         (clip-str 0 "abcdefg")))
    (is (= "a"        (clip-str 1 "abcdefg")))
    (is (= "ab"       (clip-str 2 "abcdefg")))
    (is (= "abc"      (clip-str 3 "abcdefg")))
    (is (= "abcd"     (clip-str 4 "abcdefg")))
    (is (= "abcde"    (clip-str 5 "abcdefg"))))
  (testing "two strings"
    (is (= ""         (clip-str 0 "abcdefg")))
    (is (= "a"        (clip-str 1 "abcdefg")))
    (is (= "ab"       (clip-str 2 "abcdefg")))
    (is (= "abc"      (clip-str 3 "abcdefg")))
    (is (= "abcd"     (clip-str 4 "abcdefg")))
    (is (= "abcde"    (clip-str 5 "abcdefg"))))
  (testing "two strings & char"
    (is (= ""         (clip-str 0 "ab" \c "defg")))
    (is (= "a"        (clip-str 1 "ab" \c "defg")))
    (is (= "ab"       (clip-str 2 "ab" \c "defg")))
    (is (= "abc"      (clip-str 3 "ab" \c "defg")))
    (is (= "abcd"     (clip-str 4 "ab" \c "defg")))
    (is (= "abcde"    (clip-str 5 "ab" \c "defg"))))
  (testing "two strings & digit"
    (is (= ""         (clip-str 0 "ab" 9 "defg")))
    (is (= "a"        (clip-str 1 "ab" 9 "defg")))
    (is (= "ab"       (clip-str 2 "ab" 9 "defg")))
    (is (= "ab9"      (clip-str 3 "ab" 9 "defg")))
    (is (= "ab9d"     (clip-str 4 "ab" 9 "defg")))
    (is (= "ab9de"    (clip-str 5 "ab" 9 "defg"))))
  (testing "vector"
    (is (= ""               (clip-str  0 [1 2 3 4 5] )))
    (is (= "["              (clip-str  1 [1 2 3 4 5] )))
    (is (= "[1"             (clip-str  2 [1 2 3 4 5] )))
    (is (= "[1 2"           (clip-str  4 [1 2 3 4 5] )))
    (is (= "[1 2 3 4"       (clip-str  8 [1 2 3 4 5] )))
    (is (= "[1 2 3 4 5]"    (clip-str 16 [1 2 3 4 5] ))))
  (testing "map"
    (is (= ""               (clip-str  0 (sorted-map :a 1 :b 2) )))
    (is (= "{"              (clip-str  1 (sorted-map :a 1 :b 2) )))
    (is (= "{:"             (clip-str  2 (sorted-map :a 1 :b 2) )))
    (is (= "{:a "           (clip-str  4 (sorted-map :a 1 :b 2) )))
    (is (= "{:a 1, :"       (clip-str  8 (sorted-map :a 1 :b 2) )))
    (is (= "{:a 1, :b 2}"   (clip-str 16 (sorted-map :a 1 :b 2) ))))
  (testing "set"
    (let [tst-set (sorted-set 5 4 3 2 1) ]
      (is (= ""             (clip-str  0 tst-set )))
      (is (= "#"            (clip-str  1 tst-set )))
      (is (= "#{"           (clip-str  2 tst-set )))
      (is (= "#{1 "         (clip-str  4 tst-set )))
      (is (= "#{1 2 3 "     (clip-str  8 tst-set )))
      (is (= "#{1 2 3 4 5}" (clip-str 16 tst-set )))))
)

(deftest t-keep-if
  (is (= [1 3 5] (keep-if odd?  (range 6))))
  (is (= [0 2 4] (keep-if even? (range 6)))))

(tst/defspec ^:slow t-keep-if-drop-if 9999
  (prop/for-all [vv (gen/vector gen/int) ]
    (let [even-1    (keep-if even?  vv)
          odd-1     (keep-if odd?   vv)
          even-2    (drop-if odd?   vv)
          odd-2     (drop-if even?  vv) 
          even-filt (filter  even?  vv)
          odd-rem   (remove  even?  vv) ]
      (and  (= even-1 even-2 even-filt)
            (= odd-1  odd-2  odd-rem)))))

