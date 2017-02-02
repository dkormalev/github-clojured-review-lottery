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

(defn issues-list []
  (let [all-issues (github/request tentacles.issues/my-issues {:filter (if (settings/value :only-subscribed-issues) "subscribed" "all")})]
    (filter #(and (:pull_request %) (not (get-in % [:repository :fork])) (= (:state %) "open")) all-issues)))

(defn assign-issue [{issue-id :number, issue-url :html_url,
                     {assignee :login} :assignee,
                     {repo-name :name, {repo-owner :login} :owner} :repository :as issue}
                    team-name]
  (let [options {:labels (list constants/in-review-label)}
        options (if (nil? assignee)
                  (assoc options :assignee (lottery/select-reviewer issue team-name))
                  options)]
    (if-not (nil? assignee)
      (do
        (lottery/increment-reviewer-score assignee)
        (utils/println issue-url "already assigned to" assignee))
      (utils/println "Assigning" issue-url "to" (:assignee options)))
    (github/request tentacles.issues/edit-issue repo-owner repo-name issue-id options)))

(defn mark-issue-as-reviewed-if-needed [{issue-id :number, issue-url :html_url,
                                         {assignee :login} :assignee,
                                         {repo-name :name, {repo-owner :login} :owner} :repository}]
  (let [comments (github/request tentacles.issues/issue-comments repo-owner repo-name issue-id)
        has-review-done-comment (and (not (nil? assignee))
                                     (some #(and (= (get-in % [:user :login]) assignee)
                                                 (= (string/trim (:body %)) constants/review-done-comment))
                                           comments))]
    (if has-review-done-comment
      (do
        (utils/println issue-url "successfully reviewed by" assignee)
        (github/request tentacles.issues/edit-issue repo-owner repo-name issue-id {:labels (list constants/reviewed-label)}))
      (utils/println issue-url "still not reviewed by" assignee))))

(defn issues-to-be-assigned [issues]
  (let [selector (fn [{:keys [labels]}]
                   (let [labels (into #{} (map :name labels))]
                     (and (not (contains? labels constants/in-review-label))
                          (not (contains? labels constants/reviewed-label)))))]
    (filter selector issues)))

(defn issues-to-be-checked-for-completed-review [issues]
  (let [selector (fn [{:keys [labels comments] or {:labels nil :comments 0}}]
                   (let [labels (into #{} (map :name labels))]
                     (and (contains? labels constants/in-review-label)
                          (> comments 0))))]
    (filter selector issues)))
