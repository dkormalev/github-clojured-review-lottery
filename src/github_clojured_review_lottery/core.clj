(ns github-clojured-review-lottery.core
  (:gen-class)
  (:require [tentacles core])
  (:require [github-clojured-review-lottery
             [utils :as utils]
             [settings :as settings]
             [teams :as teams]
             [lottery :as lottery]
             [issues :as issues]
             [github :as github]]))

(defn issues-reducer [acc issue]
  (let [repo-name (get-in issue [:repository :full_name])
        team (-> repo-name teams/teams-for-repo first)
        team-issues (acc team)]
    (cond
      (nil? team) acc
      (nil? team-issues) (assoc acc team [issue])
      :default (update acc team conj issue))))

(defn issues-mapper [[team issues]]
  (let [for-assignment (issues/issues-to-be-assigned issues)
        for-check (issues/issues-to-be-checked-for-completed-review issues)]
    (utils/println team ":" (vec (map :html_url for-assignment)) (vec (map :html_url for-check)))
    (doseq [issue for-assignment] (issues/assign-issue issue team))
    (doseq [issue for-check] (issues/mark-issue-as-reviewed-if-needed issue))))

(defn check-issues []
  (println "Starting check at" (.toString (new java.util.Date)))
  (->> (issues/issues-list)
       teams/filter-related-issues
       (reduce issues-reducer {})
       (pmap issues-mapper)
       doall))

(defn -main [& args]
  (println "Teams:" (map #(vector (first %) (map :login (second %))) teams/teams-members))
  (println "Ubers:" (map #(vector (first %) (map :login (second %))) teams/teams-ubers))
  (while true
    (check-issues)
    (github/save-cache)
    (lottery/save-scores)
    (println "Current limit:" (tentacles.core/rate-limit {:auth (str (settings/value :api-token) ":x-oauth-basic")}))
    (Thread/sleep (* 1000 (settings/value :interval))))
  (shutdown-agents))
