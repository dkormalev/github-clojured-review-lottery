(defproject github-clojured-review-lottery "1.0.0-SNAPSHOT"
  :description "GitHub Code Review Lottery"
  :url "https://github.com/dkormalev/github-clojured-review-lottery"
  :license {:name "MIT License"
            :url "https://opensource.org/licenses/MIT"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojars.bostonaholic/tentacles "0.5.2-SNAPSHOT"]]
  :main ^:skip-aot github-clojured-review-lottery.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
