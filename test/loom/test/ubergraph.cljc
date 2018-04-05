(ns loom.test.ubergraph
  (:require [clojure.test :refer :all]
            [loom.ubergraph :as ug]
            [loom.graph :as lg]))

(deftest t
  (is (doto (ug/multigraph [1 2])
        (-> (lg/edges) (prn "UG"))
        (-> (lg/nodes) (prn "UG")))))
