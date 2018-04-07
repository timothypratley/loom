(defproject aysylu/loom "1.1.0-SNAPSHOT"
  :min-lein-version "2.0.0"
  :description "Graph library for Clojure"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [org.clojure/data.priority-map "0.0.7"]
                 [tailrecursion/cljs-priority-map "1.2.1"]
                 [lein-doo "0.1.10"]]
  :url "https://github.com/aysylu/loom"
  :test-selectors {:default (fn [m] (not (:test-check-slow m)))
                   :all (constantly true)
                   :test-check-slow :test-check-slow}

  :profiles {:dev [:cljs
                   {:dependencies [[org.clojure/test.check "0.9.0"]]
                    :plugins [[com.jakemccrary/lein-test-refresh "0.22.0"]]
                    :repl-options {:init (set! *print-length* 50)}}]

             :cljs {:dependencies [[org.clojure/clojurescript "1.10.238"]]
                    :plugins [[lein-cljsbuild "1.1.7" :exclusions [org.clojure/clojure]]
                              [lein-doo "0.1.10"]]
                    :doo {:build "node-dev"}
                    :cljsbuild {:builds
                                {"node-dev"
                                 {:source-paths ["src", "test"]
                                  :compiler {:output-to "target/loom.js"
                                             :optimizations :none
                                             :pretty-print true
                                             :target :nodejs
                                             :main loom.test.runner}}
                                 "node-test"
                                 {:id "min"
                                  :source-paths ["src", "test"]
                                  :compiler {:output-to "target/loom.js"
                                             :optimizations :advanced
                                             :pretty-print false
                                             :target :nodejs
                                             :main loom.test.runner}}}}}}

  :aliases {"test-all" ["do" "clean," "test" ":all," "cljs-test"]
            "cljs-test" ["doo" "node" "node-test" "once"]
            "release" ["do" "clean," "with-profile" "default" "deploy" "clojars"]}

  :plugins  [[codox "0.10.3"]]
  :codox  {:src-dir-uri "https://github.com/aysylu/loom/blob/master/"
           :src-linenum-anchor-prefix "L"
           :exclude loom.multigraph})
