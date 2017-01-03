(ns github-clojured-review-lottery.teams
  (:gen-class)
  (:require [clojure.string :as string])
  (:require [tentacles repos orgs users issues])
  (:require [github-clojured-review-lottery
             [utils :as utils]
             [constants :as constants]
             [settings :as settings]
             [github :as github]]))

(def ^:private denied-repos (atom {}))

(let [all-user-teams (github/request tentacles.users/my-teams)]
  (def ^:private teams (filter #(some #{(:name %)} (settings/value :teams)) all-user-teams))
  (def ^:private uber-team (first (filter #(= (:name %) (settings/value :uber-team)) all-user-teams)))
  (def ^:private teams-names (set (map :name teams))))

(let [me (:login (github/request tentacles.users/me))
      ubers (set (map :login (github/request tentacles.orgs/team-members (:id uber-team))))
      members-fetcher (fn [team]
                        (let [team-name (:name team)
                              members (github/request tentacles.orgs/team-members (:id team))]
                          [team-name (filter #(not= (:login %) me) members)]))]
  (def teams-members (into {} (pmap members-fetcher teams)))
  (def teams-ubers (into {} (for [[team-name members] teams-members]
                              [team-name (filter #(contains? ubers (:login %)) members)]))))

(defn ^:private fetch-teams-for-repo
  ([repo-full-name] (let [[repo-owner repo-name] (string/split repo-full-name #"/" 2)] (fetch-teams-for-repo repo-owner repo-name)))
  ([repo-owner repo-name]
   (let [repo-teams (github/request tentacles.repos/teams repo-owner repo-name)]
     (if (and (:body repo-teams) (:headers repo-teams) (:status repo-teams))
       (let [full-name (str repo-owner "/" repo-name)
             current-date (.getTime (java.util.Date.))]
         (swap! denied-repos assoc full-name current-date)
         nil)
       (filter #(contains? teams-names %) (map :name repo-teams))))))

(let [fetcher (fn [team]
                (let [team-id (:id team)
                      team-repos (github/request tentacles.orgs/list-team-repos team-id)]
                  (map :full_name (remove :fork team-repos))))
      all-repositories (set (flatten (pmap fetcher teams)))]
  (def ^:private repos-teams (atom (into {} (pmap #(vector % (fetch-teams-for-repo %)) all-repositories)))))

(defn teams-for-repo [repo]
  (@repos-teams repo))

(defn update-repo-teams [repo-full-name]
  (let [fetched-teams (fetch-teams-for-repo repo-full-name)]
    (if (not (nil? fetched-teams)) (swap! repos-teams assoc repo-full-name fetched-teams))))

(defn ^:private create-repo-labels-if-needed [repo-full-name]
  (let [[repo-owner repo-name] (string/split repo-full-name #"/" 2)
        labels (github/request tentacles.issues/repo-labels repo-owner repo-name)
        labels (into #{} (map :name labels))]
    (if (not (contains? labels constants/in-review-label))
      (github/request tentacles.issues/create-label repo-owner repo-name constants/in-review-label "eb6420"))
    (if (not (contains? labels constants/reviewed-label))
      (github/request tentacles.issues/create-label repo-owner repo-name constants/reviewed-label "00aa00"))))

(defn filter-related-issues [issues]
  (let [repos (reduce #(conj %1 (get-in %2 [:repository :full_name])) #{} issues)
        denied-repo-checker (fn [repo]
                              (if (nil? (@denied-repos repo))
                                true
                                (let [current-diff (- (.getTime (java.util.Date.)) (@denied-repos repo))
                                      result (> current-diff constants/denied-repos-timeout)
                                      _ (if result (swap! denied-repos dissoc repo))]
                                  result)))
        repos (doall (filter denied-repo-checker repos))
        _ (doall (pmap update-repo-teams repos))
        repos (doall (filter #(nil? (@denied-repos %)) repos))
        _ (doall (pmap create-repo-labels-if-needed repos))
        all-checkable-repos (into #{} (keys @repos-teams))]
    (filter #(contains? all-checkable-repos (get-in % [:repository :full_name])) issues)))
