(ns github-clojured-review-lottery.issues
  (:gen-class)
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

(defn assign-issue [issue team-name]
  (let [options {:labels (list constants/in-review-label)}
        options (if (nil? (:assignee issue))
                  (assoc options :assignee (lottery/select-reviewer issue team-name))
                  options)]
    (if (not (nil? (:assignee issue)))
      (do
        (lottery/increment-reviewer-score (get-in issue [:assignee :login]))
        (utils/println (:html_url issue) "already assigned to" (get-in issue [:assignee :login])))
      (utils/println "Assigning" (:html_url issue) "to" (:assignee options)))
    (github/request tentacles.issues/edit-issue
                    (get-in issue [:repository :owner :login])
                    (get-in issue [:repository :name])
                    (:number issue)
                    options)))

(defn mark-issue-as-reviewed-if-needed [issue]
  (let [comments (github/request tentacles.issues/issue-comments
                                 (get-in issue [:repository :owner :login])
                                 (get-in issue [:repository :name])
                                 (:number issue))
        assignee (get-in issue [:assignee :login])
        has-review-done-comment (and (not (nil? assignee))
                                     (some #(and (= (get-in % [:user :login]) assignee)
                                                 (= (string/trim (:body %)) constants/review-done-comment))
                                           comments))]
    (if has-review-done-comment
      (do
        (utils/println (:html_url issue) "successfully reviewed by" assignee)
        (github/request tentacles.issues/edit-issue
                    (get-in issue [:repository :owner :login])
                    (get-in issue [:repository :name])
                    (:number issue)
                    {:labels (list constants/reviewed-label)}))
      (utils/println (:html_url issue) "still not reviewed by" assignee))))

(defn issues-to-be-assigned [issues]
  (let [selector (fn [issue]
                   (let [labels (into #{} (map :name (:labels issue)))]
                     (and (not (contains? labels constants/in-review-label))
                          (not (contains? labels constants/reviewed-label)))))]
    (filter selector issues)))

(defn issues-to-be-checked-for-completed-review [issues]
  (let [selector (fn [issue]
                   (let [labels (into #{} (map :name (:labels issue)))
                         comments (get issue :comments 0)]
                     (and (contains? labels constants/in-review-label)
                          (> comments 0))))]
    (filter selector issues)))
