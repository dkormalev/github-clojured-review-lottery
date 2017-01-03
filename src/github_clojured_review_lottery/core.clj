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

(defn issues-by-teams [issues]
  ; Think about randomizing across teams associated with repo or something like it
  (let [reducer (fn [acc issue]
                  (let [repo-name (get-in issue [:repository :full_name])
                        team (first (teams/teams-for-repo repo-name))
                        team-issues (acc team)]
                    (cond
                      (nil? team) acc
                      (nil? team-issues) (assoc acc team [issue])
                      :default (update acc team conj issue))))]
    (reduce reducer {} issues)))

(defn check-issues []
  (while (constantly true)
    (println "Starting check at" (.toString (new java.util.Date)))
    (let [issues (-> (issues/issues-list)
                     (teams/filter-related-issues)
                     (issues-by-teams))
          mapper (fn [[team issues]]
                   (let [for-assignment (issues/issues-to-be-assigned issues)
                         for-check (issues/issues-to-be-checked-for-completed-review issues)
                         _ (utils/println team ":" (vec (map :html_url for-assignment)) (vec (map :html_url for-check)))]
                     (doseq [issue for-assignment] (issues/assign-issue issue team))
                     (doseq [issue for-check] (issues/mark-issue-as-reviewed-if-needed issue))))]
      (doall (pmap mapper issues))
      (utils/println "Current limit: " (tentacles.core/rate-limit  {:auth (str (settings/value :api-token) ":x-oauth-basic")}))
      (github/save-cache)
      (lottery/save-scores)
      (Thread/sleep (* 1000 (settings/value :interval))))))

(defn -main [& args]
  (println "Teams:" (map #(vector (first %) (map :login (second %))) teams/teams-members))
  (println "Ubers:" (map #(vector (first %) (map :login (second %))) teams/teams-ubers))
  (check-issues)
  (shutdown-agents))
