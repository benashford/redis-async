(defproject redis-async "0.1.0-SNAPSHOT"
  :description "An asynchronous Redis client"
  :url "https://github.com/benashford/redis-async"
  :license {:name         "Apache Licence 2.0"
            :url          "http://www.apache.org/licenses/LICENSE-2.0"
            :distribution :repo}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [aleph "0.4.0-beta3"]
                 [cheshire "5.4.0"]
                 [gloss "0.2.4"]
                 [criterium "0.4.3"]]
  :jvm-opts ^:replace [])
