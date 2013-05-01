(defproject davidwclin/persister "0.0.1"
  :description "Simple Persistence for Clojure"
  :url "https://github.com/davidwclin/Simple-Persistence-for-Clojure" 
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.clojure/java.jdbc "0.2.3"]
                 [postgresql "9.1-901.jdbc4"]]
  :profiles { :dev { :dependencies [[org.apache.derby/derby "10.9.1.0"]]
                    }}
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  )
