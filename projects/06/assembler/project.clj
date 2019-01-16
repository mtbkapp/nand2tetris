(defproject assembler "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [instaparse "1.4.9"]]
  :main ^:skip-aot assembler.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
