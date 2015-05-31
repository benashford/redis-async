(defproject redis-async "0.1.3-SNAPSHOT"
  :description "An asynchronous Redis client"
  :url "https://github.com/benashford/redis-async"
  :license {:name         "Apache Licence 2.0"
            :url          "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}
  :java-source-paths ["jresp/src/main/java"]
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [io.netty/netty-all "4.0.28.Final"]
                 [cheshire "5.4.0"]]
  :profiles {:dev {:dependencies [[criterium "0.4.3"]]
                   :jvm-opts ^:replace []}}
  :aliases {"bench-repl" ["with-profile" "benchmark" "repl" ":headless"]})
