(ns github-clojured-review-lottery.github
  (:require [clojure.string :as string])
  (:require [tentacles.core])
  (:require [github-clojured-review-lottery.settings :as settings])
  (:require [github-clojured-review-lottery.utils :as utils]))

(def ^:private cache (atom {}))
(def ^:private map-types #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap})

(defn ^:private do-single-request [request args]
  (let [cache-key [(-> request str (string/split #"@" 2) first) args]
        additional-options {:etag (get-in @cache [cache-key :etag])
                            :auth (str (settings/value :api-token) ":x-oauth-basic")}
        last-arg (last args)
        full-args (if (and (not (nil? last-arg)) (contains? map-types (type last-arg)))
                    (concat (drop-last args) (list (merge last-arg additional-options)))
                    (concat args (list additional-options)))
        result (apply request full-args)]
    (if (= result :tentacles.core/not-modified)
      (get-in @cache [cache-key :result])
      (do
        (utils/println "Cache miss" request args)
        (if-let [etag (:etag (tentacles.core/api-meta result))]
          (swap! cache assoc cache-key {:result result :etag etag}))
        result))))

(defn ^:private fill-args [args page]
  (let [cacheable-options {:page page :per-page 100}
        last-arg (last args)
        args (if (and (not (nil? last-arg)) (contains? map-types (type last-arg)))
               (concat (drop-last args) (list (merge last-arg cacheable-options)))
               (concat args (list cacheable-options)))]
    (vec args)))

(defn ^:private filled-list? [c]
  (and (list? c)
       (not (empty? c))))

(defn ^:private no-raw-http-data? [c]
  (and (nil? (:status c))
       (nil? (:headers c))
       (nil? (:body c))))

(defn request [request & args]
  (loop [page 1
         result (do-single-request request (fill-args args 1))
         final-result (list)
         last-etag ""]
    (let [current-etag (:etag (tentacles.core/api-meta result))]
      (cond
        (and (filled-list? result)
             (not= current-etag last-etag)
             (no-raw-http-data? result)) (recur (inc page)
                                                (do-single-request request (fill-args args (inc page)))
                                                (concat final-result result)
                                                current-etag)
        (and (empty? final-result) (= page 1)) result
        :default final-result)
      )))

(defn load-cache []
  (reset! cache
          (binding [*read-eval* false]
            (let [file-name "requests.cache"
                  file-exists? (.exists (clojure.java.io/file file-name))
                  file-contents (if file-exists? (slurp file-name) "{}")
                  new-cache (read-string file-contents)
                  new-cache-map? (contains? map-types (type new-cache))]
              (if new-cache-map? new-cache {})))))

(defn save-cache []
  (spit "requests.cache" (str @cache)))

(load-cache)
