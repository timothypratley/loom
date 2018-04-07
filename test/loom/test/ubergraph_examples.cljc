(ns loom.test.ubergraph-examples
  (:require [#?(:clj clojure.test :cljs cljs.test)
             :refer (deftest is are testing)]
            [loom.ubergraph :as uber]
            [loom.graph :as lg]
            [loom.attr :as la]))

(def graph1
  (uber/graph [:a :b] [:a :c] [:b :d]))

(def graph2
  (uber/graph [:a :b 2] [:a :c 3] [:b :d 4]))

(def graph3
  (uber/graph [:a :b {:weight 2 :cost 200 :distance 10}]
              [:a :c {:weight 3 :cost 300 :distance 20}]))

(def graph4
  (uber/add-directed-edges graph2 [:a :d 8]))

#_#_#_
(deftest test-equal-graphs?
  (are [x y] (= x y)
       (uber/graph [:a :b] [:a :c] [:b :d]) (uber/graph [:a :b] [:a :c] [:b :d])
       (uber/graph [:a :b 2] [:a :c 3] [:b :d 4]) (uber/graph [:a :b 2] [:a :c 3] [:b :d 4])

       (uber/graph [:a :b {:weight 2 :cost 200 :distance 10}]
                   [:a :c {:weight 3 :cost 300 :distance 20}])
       (uber/graph [:a :b {:weight 2 :cost 200 :distance 10}]
              [:a :c {:weight 3 :cost 300 :distance 20}])

       (uber/multigraph [:a :b] [:a :b] [:a :b {:color :red}])
       (uber/multigraph [:a :b] [:a :b {:color :red}] [:a :b])

       (la/add-attr graph1 :a :b :weight 3)
       (uber/graph [:a :b 3] [:a :c] [:b :d])

       (lg/remove-edges graph1 [:b :d])
       (uber/graph [:a :b] [:a :c] :d)

       (lg/remove-edges graph1 [:d :b])
       (uber/graph [:a :b] [:a :c] :d)

       (uber/graph [:a {:counter 1}])
       (uber/graph [:a {:counter 1}])))


(deftest test-notequal-graphs?
  (let [g (uber/graph :a)]
    (is (not= (uber/set-attrs g :a {:counter 1})
              (uber/set-attrs g :a {:counter 2})))
    (is (not= (uber/graph [:a :b {:weight 2 :cost 200 :distance 10}]
                          [:a :c {:weight 3 :cost 300 :distance 20}])
              (uber/graph [:a :b {:weight 2 :cost 200 :distance 10}]
                          [:a :c {:weight 3 :cost 400 :distance 20}])))))

(deftest test-merge-attrs
  (is (= {:color :red, :n 2}
         (la/attrs (lg/add-edges (uber/graph "a" "b") ["a" "b" {:color :red}] ["b" "a" {:n 2}]) ["a" "b"])))
  (is (= true
         (= (uber/graph 1)
            (uber/add-attrs (uber/graph 1) 1 {})))))
