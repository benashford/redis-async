(defproject redis-async "0.3.0"
  :description "An asynchronous Redis client"
  :url "https://github.com/benashford/redis-async"
  :license {:name         "Apache Licence 2.0"
            :url          "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}
  :java-source-paths ["jresp/src/main/java"]
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cheshire "5.5.0"]]
  :profiles {:dev {:dependencies [[criterium "0.4.3"]]
                   :jvm-opts ^:replace []}}
  :aliases {"bench-repl" ["with-profile" "benchmark" "repl" ":headless"]})
