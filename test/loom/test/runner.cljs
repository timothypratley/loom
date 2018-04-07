(ns loom.test.runner
  (:require-macros [doo.runner :refer [doo-all-tests]])
  (:require doo.runner
            loom.test.alg
            loom.test.alg-generic
            loom.test.attr
            loom.test.derived
            loom.test.flow
            loom.test.graph
            loom.test.label
            loom.test.ubergraph
            loom.test.ubergraph-examples))

(doo-all-tests)
