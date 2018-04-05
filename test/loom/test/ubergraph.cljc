(ns loom.test.ubergraph
  (:require [clojure.test :refer :all]
            [loom.ubergraph :as ug]))

(deftest t
  (is (ug/multigraph [1 2])))
