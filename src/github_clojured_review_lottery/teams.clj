(ns github-clojured-review-lottery.teams
  (:require [clojure.string :as string])
  (:require [tentacles repos orgs users issues])
  (:require [github-clojured-review-lottery
             [utils :as utils]
             [constants :as constants]
             [settings :as settings]
             [github :as github]]))

(def ^:private denied-repos-timeout (* 1000 60 60))

(def ^:private teams-for-repos (atom {}))
(def ^:private denied-repos (atom {}))

(let [all-user-teams (github/request tentacles.users/my-teams)
      ubers-filter (fn [{team-name :name}]
                     (= team-name (settings/value :uber-team)))]
  (def ^:private teams (filter #(some #{(:name %)} (settings/value :teams)) all-user-teams))
  (def ^:private uber-team (->> all-user-teams
                                (filter ubers-filter)
                                first))
  (def ^:private teams-names (->> teams
                                  (map :name)
                                  set)))

(let [me (:login (github/request tentacles.users/me))
      ubers (set (map :login (github/request tentacles.orgs/team-members (:id uber-team))))
      members-fetcher (fn [{team-name :name, team-id :id}]
                        (let [members (github/request tentacles.orgs/team-members team-id)]
                          [team-name (filter #(not= (:login %) me) members)]))]
  (def teams-members (into {} (pmap members-fetcher teams)))
  (def teams-ubers (into {} (for [[team-name members] teams-members]
                              [team-name (filter #(contains? ubers (:login %)) members)]))))

(defn ^:private raw-http-data? [c]
  (not (and (nil? (:status c))
            (nil? (:headers c))
            (nil? (:body c)))))

(defn ^:private repo->teams
  ([repo-full-name]
   (let [[repo-owner repo-name] (string/split repo-full-name #"/" 2)]
     (repo->teams repo-owner repo-name)))
  ([repo-owner repo-name]
   (let [repo-teams (github/request tentacles.repos/teams repo-owner repo-name)]
     (if (raw-http-data? repo-teams)
       (let [full-name (str repo-owner "/" repo-name)]
         (swap! denied-repos assoc full-name (.getTime (java.util.Date.)))
         nil)
       (filter #(contains? teams-names %) (map :name repo-teams))))))

(defn ^:private team->repos [{team-id :id}]
  (->> team-id
       (github/request tentacles.orgs/list-team-repos)
       (remove :fork)
       (map :full_name)))

(defn ^:private create-repo-labels-if-needed [repo-full-name]
  (let [[repo-owner repo-name] (string/split repo-full-name #"/" 2)
        labels (->> (github/request tentacles.issues/repo-labels repo-owner repo-name)
                    (map :name)
                    (into #{}))]
    (when-not (contains? labels constants/in-review-label)
      (github/request tentacles.issues/create-label
                      repo-owner repo-name
                      constants/in-review-label "eb6420"))
    (when-not (contains? labels constants/reviewed-label)
      (github/request tentacles.issues/create-label
                      repo-owner repo-name
                      constants/reviewed-label "00aa00")))
  repo-full-name)

(defn ^:private repo-denied? [repo-name]
  (if-not (@denied-repos repo-name)
    false
    (let [current-diff (- (.getTime (java.util.Date.)) (@denied-repos repo-name))
          still-denied? (< current-diff denied-repos-timeout)]
      (when-not still-denied? (swap! denied-repos dissoc repo-name))
      still-denied?)))

(defn teams-for-repo [repo]
  (@teams-for-repos repo))

(defn update-repo-teams [repo-full-name]
  (if-let [fetched-teams (repo->teams repo-full-name)]
    (swap! teams-for-repos assoc repo-full-name fetched-teams))
  repo-full-name)

(defn filter-related-issues [issues]
  (let [repos (->> issues
                   (map #(get-in % [:repository :full_name]))
                   (into #{})
                   (remove repo-denied?)
                   (pmap update-repo-teams)
                   doall
                   (filter #(nil? (@denied-repos %)))
                   (pmap create-repo-labels-if-needed)
                   doall)
        all-checkable-repos (into #{} (keys @teams-for-repos))]
    (filter #(contains? all-checkable-repos (get-in % [:repository :full_name])) issues)))

(reset! teams-for-repos (as-> teams x
                          (pmap team->repos x)
                          (flatten x)
                          (set x)
                          (pmap #(vector % (repo->teams %)) x)
                          (into {} x)))
