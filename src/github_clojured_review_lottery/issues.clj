(ns github-clojured-review-lottery.issues
  (:require [tentacles issues])
  (:require [clojure.string :as string])
  (:require [github-clojured-review-lottery
             [utils :as utils]
             [constants :as constants]
             [settings :as settings]
             [teams :as teams]
             [lottery :as lottery]
             [github :as github]]))

(def ^:private review-done-comment "+1")

(defn ^:private contains-any-lottery-label? [{labels :labels}]
  (as-> labels l
        (map :name l)
        (into #{} l)
        (not-any? l [constants/in-review-label constants/reviewed-label])))

(defn ^:private contains-in-review-label? [{labels :labels}]
  (as-> labels l
        (map :name l)
        (into #{} l)
        (contains? l constants/in-review-label)))

(defn ^:private has-comments? [{comments :comments, :default {:comments 0}}]
  (> comments 0))

(defn issues-list []
  (->> {:filter (if (settings/value :only-subscribed-issues) "subscribed" "all")}
       (github/request tentacles.issues/my-issues)
       (filter #(and (:pull_request %)
                     (not (get-in % [:repository :fork]))
                     (= (:state %) "open")))))

(defn assign-issue [{issue-id :number, issue-url :html_url,
                     {assignee :login} :assignee,
                     {repo-name :name, {repo-owner :login} :owner} :repository, :as issue}
                    team-name]
  (let [new-assignee (or assignee (lottery/select-reviewer issue team-name))
        options {:labels (list constants/in-review-label), :assignee new-assignee}]
    (if (nil? assignee)
      (utils/println "Assigning" issue-url "to" new-assignee)
      (utils/println issue-url "already assigned to" assignee))
    (lottery/increment-reviewer-score new-assignee)
    (github/request tentacles.issues/edit-issue repo-owner repo-name issue-id options)))

(defn mark-issue-as-reviewed-if-needed [{issue-id :number, issue-url :html_url,
                                         {assignee :login} :assignee,
                                         {repo-name :name, {repo-owner :login} :owner} :repository}]
  (when-not (nil? assignee)
    (when (->> (github/request tentacles.issues/issue-comments repo-owner repo-name issue-id)
               (some #(and (= (get-in % [:user :login]) assignee)
                           (= (string/trim (:body %)) review-done-comment))))
      (utils/println issue-url "successfully reviewed by" assignee)
      (github/request tentacles.issues/edit-issue
                      repo-owner repo-name issue-id
                      {:labels (list constants/reviewed-label)}))))

(defn issues-to-be-assigned [issues]
  (filter contains-any-lottery-label? issues))

(defn issues-to-be-checked-for-completed-review [issues]
  (filter #(and (contains-in-review-label? %) (has-comments? %)) issues))
