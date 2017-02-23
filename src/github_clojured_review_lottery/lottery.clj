(ns github-clojured-review-lottery.lottery
  (:require [tentacles repos])
  (:require [github-clojured-review-lottery
             [utils :as utils]
             [constants :as constants]
             [settings :as settings]
             [teams :as teams]
             [github :as github]]))

(def ^:private map-types #{clojure.lang.PersistentArrayMap clojure.lang.PersistentHashMap})
(def ^:private scores (atom {}))

(defn ^:private randomizer-reducer [{found-min :min, found-list :list, :as acc} [reviewer score]]
  (cond
    (or (< score found-min) (empty? found-list)) {:min score :list [reviewer]}
    (= score found-min) (update acc :list conj reviewer)
    :default acc))

(defn ^:private randomizer [reviewers]
  (condp = (count reviewers)
    0 nil
    1 (first (first reviewers))
    (let [{less-scored :list} (reduce randomizer-reducer {:min 0 :list []} reviewers)]
      (when-not (zero? (count less-scored))
        (get less-scored (rand-int (count less-scored)))))))

(defn ^:private prepare-reviewers [{{issue-author :login} :user} team scores-snapshot]
  (->> team
       (map (fn [{reviewer :login}] [reviewer (get scores-snapshot reviewer 0)]))
       (remove #(= (first %) issue-author))))

(defn ^:private contributions-for-repo [repo-owner repo-name]
  (reduce (fn [{:keys [sum contributors]}
               {:keys [contributions login]}]
            {:sum (+ sum contributions),
             :contributors (assoc contributors login contributions)})
          {:sum 0 :contributors {}}
          (github/request tentacles.repos/contributors repo-owner repo-name)))

(defn increment-reviewer-score [reviewer]
  (swap! scores update reviewer (fnil inc 0)))

(defmulti select-reviewer (fn [_ _] (settings/value :lottery-mode)) :default :random)

(defmethod select-reviewer :random [{{issue-author :login} :user, :as issue} team-name]
  (let [scores-snapshot @scores]
    (or (randomizer (prepare-reviewers issue (teams/teams-members team-name) scores-snapshot))
        (randomizer (prepare-reviewers issue (teams/teams-ubers team-name) scores-snapshot))
        issue-author)))

(defmethod select-reviewer :repo [{{repo-name :name, {repo-owner :login} :owner} :repository,
                                   {issue-author :login} :user, :as issue}
                                  team-name]
  (let [scores-snapshot @scores
        {:keys [sum contributors]} (contributions-for-repo repo-owner repo-name)
        contributions-threshold (-> sum
                                    (/ (count contributors))
                                    (* (settings/value :repo-lottery-factor)))
        members (prepare-reviewers issue (teams/teams-members team-name) scores-snapshot)
        members (remove #(< (get contributors (first %) 0) contributions-threshold) members)]
    (or (randomizer members)
        (randomizer (prepare-reviewers issue (teams/teams-ubers team-name) scores-snapshot))
        issue-author)))

(defn load-scores []
  (reset! scores
          (binding [*read-eval* false]
            (let [file-name "scores.cache"
                  file-exists? (.exists (clojure.java.io/file file-name))
                  file-contents (if file-exists? (slurp file-name) "{}")
                  new-scores (read-string file-contents)
                  new-scores-map? (contains? map-types (type new-scores))]
              (if new-scores-map? new-scores {})))))

(defn save-scores []
  (spit "scores.cache" (str @scores)))

(load-scores)
