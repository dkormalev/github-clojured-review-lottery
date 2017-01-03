(ns github-clojured-review-lottery.utils
  (:refer-clojure :exclude [println]))

(defn println [& more]
  (let [more (doall (map #(cond
                            (= (type %) clojure.lang.LazySeq) (doall %)
                            (nil? %) "nil"
                            :default %)
                         more))]
    (.write *out* (str (clojure.string/join " " more) "\n"))))
