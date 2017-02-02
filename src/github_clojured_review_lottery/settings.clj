(ns github-clojured-review-lottery.settings)

(def ^:private config (let [file-contents (if (.exists (clojure.java.io/file "config.clj")) (slurp "config.clj") "{}")
                            raw-config (binding [*read-eval* false] (read-string file-contents))]
                        (when-not (and (:api-token raw-config) (count (:teams raw-config)))
                          (do
                            (println "No :api-token or :teams found in config. Stopping lottery")
                            (System/exit 0)))
                        raw-config))

(def ^:private defaults
  {:lottery-mode :random
   :interval 10
   :uber-team ""
   :teams []
   :only-subscribed-issues false
   :repo-lottery-factor 0.5})

(defn value
  ([key] (value key (defaults key)))
  ([key default-value] (config key default-value)))
