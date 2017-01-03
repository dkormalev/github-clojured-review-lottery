(ns github-clojured-review-lottery.lottery
  (:gen-class)
  (:require [tentacles repos])
  (:require [github-clojured-review-lottery
             [utils :as utils]
             [constants :as constants]
             [settings :as settings]
             [teams :as teams]
             [github :as github]]))

(def ^:private map-types #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap})
(def ^:private scores (atom {}))

(defn ^:private randomizer [reviewers]
  (condp = (count reviewers)
    0 nil
    1 (first (first reviewers))
    (let [reducer (fn [acc r]
                    (let [[reviewer score] r]
                      (cond
                        (or (< score (:min acc)) (empty? (:list acc))) {:min score :list [reviewer]}
                        (= score (:min acc)) (update acc :list conj reviewer)
                        :default acc)))
          {less-scored :list} (reduce reducer {:min 0 :list []} reviewers)]
      (if (= 0 (count less-scored)) nil (get less-scored (rand-int (count less-scored)))))))

(defn increment-reviewer-score [reviewer]
  (swap! scores update reviewer (fnil inc 0)))

(defmulti select-reviewer (fn [_ _] (settings/value :lottery-mode)) :default :random)

(defmethod select-reviewer :random [issue team-name]
  (let [issue-author (get-in issue [:user :login])
        scores-snapshot @scores
        members (->> (teams/teams-members team-name)
                     (map #(vector (:login %) (get scores-snapshot (:login %) 0)))
                     (remove #(= (first %) issue-author)))
        ubers (->> (teams/teams-ubers team-name)
                   (map #(vector (:login %) (get scores-snapshot (:login %) 0)))
                   (remove #(= (first %) issue-author)))
        reviewer (randomizer members)
        reviewer (if (nil? reviewer) (randomizer ubers) reviewer)
        reviewer (if (nil? reviewer) issue-author reviewer)]
    (increment-reviewer-score reviewer)
    reviewer))

(defmethod select-reviewer :repo [issue team-name]
  (let [issue-author (get-in issue [:user :login])
        scores-snapshot @scores
        ubers (->> (teams/teams-ubers team-name)
                   (map #(vector (:login %) (get scores-snapshot (:login %) 0)))
                   (remove #(= (first %) issue-author)))
        {sum :sum, contributors :contributors} (reduce #(-> %1
                                                            (update :sum + (:contributions %2))
                                                            (update :contributors
                                                                    assoc (:login %2) (:contributions %2)))
                                                       {:sum 0 :contributors {}}
                                                       (github/request tentacles.repos/contributors
                                                                       (get-in issue [:repository :owner :login])
                                                                       (get-in issue [:repository :name])))
        contributions-threshold (-> sum
                                    (/ (count contributors))
                                    (* (settings/value :repo-lottery-factor)))
        members (->> (teams/teams-members team-name)
                     (map #(vector (:login %) (get scores-snapshot (:login %) 0)))
                     (remove #(or (= (first %) issue-author)
                                  (< (get contributors (first %) 0) contributions-threshold))))
        reviewer (randomizer members)
        reviewer (if (nil? reviewer) (randomizer ubers) reviewer)
        reviewer (if (nil? reviewer) issue-author reviewer)]
    (increment-reviewer-score reviewer)
    reviewer))


(defn load-scores []
  (reset! scores (binding [*read-eval* false] (let [file-name "scores.cache"
                                                    file-contents (if (.exists (clojure.java.io/file file-name)) (slurp file-name) "{}")
                                                    new-scores (read-string file-contents)]
                                                (if (contains? map-types (type new-scores)) new-scores {})))))

(defn save-scores []
  (spit "scores.cache" (str @scores)))

(load-scores)
