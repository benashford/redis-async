(defproject redis-async "0.3.3-SNAPSHOT"
  :description "An asynchronous Redis client"
  :url "https://github.com/benashford/redis-async"
  :license {:name         "Apache Licence 2.0"
            :url          "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}
  :java-source-paths ["jresp/src/main/java"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.2.374"]
                 [cheshire "5.5.0"]]
  :profiles {:dev {:dependencies [[criterium "0.4.3"]]
                   :jvm-opts ^:replace []}}
  :aliases {"bench-repl" ["with-profile" "benchmark" "repl" ":headless"]})
