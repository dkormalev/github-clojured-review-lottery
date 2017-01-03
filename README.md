GitHub Code Review Lottery
==========================
Utility that loops endlessly in waiting for new pull requests in given repositories and adds assignees (who supposed to review these PRs).

Prereqs
-------
* Java 1.7
* Clojure 1.8
* tentacles 0.5.2

TODO
----
* git statistics usage to select proper reviewer
* Refactor to hide github pagination in single fetch method
* Think about teams members re-fetch
* Think about database usage for storing scores/cache

Config file
-----------
Config is stored in config.clj file.
At least teams and api-token should be specified.
```clojure
{:api-token "YOUR API TOKEN"
 :teams ["FIRST TEAM", "SECOND TEAM"]
 :uber-team "UBER TEAM"
 :interval 10
 :only-subscribed-issues false
 :lottery-mode :repo
 :repo-lottery-factor 0.5}
```

License
-------
Copyright © 2017 Denis Kormalev

Distributed under MIT License.
