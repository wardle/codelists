(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]
            [borkdude.gh-release-artifact :as gh]))

(def lib 'com.eldrix/codelists)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def class-dir "target/classes")
(def jar-basis (b/create-basis {:project "deps.edn"}))
(def uber-basis (b/create-basis {:project "deps.edn"
                                 :aliases [:run]}))
(def jar-file (format "target/%s-lib-%s.jar" (name lib) version))
(def uber-file (format "target/%s-%s.jar" (name lib) version))
(def github {:org    "wardle"
             :repo   "codelists"
             :tag    (str "v" version)
             :file   uber-file
             :sha256 true})

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (println "Building" jar-file)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     jar-basis
                :src-dirs  ["src"]
                :scm       {:url                 "https://github.com/wardle/codelists"
                            :tag                 (str "v" version)
                            :connection          "scm:git:git://github.com/wardle/codelists.git"
                            :developerConnection "scm:git:ssh://git@github.com/wardle/codelists.git"}
                :pom-data  [[:description "Generate code lists using an expressive combination of SNOMED ECL, ICD-10 and ATC codes"]
                            [:developers
                             [:developer
                              [:id "wardle"] [:name "Mark Wardle"] [:email "mark@wardle.org"] [:url "https://wardle.org"]]]
                            [:organization [:name "Eldrix Ltd"]]
                            [:licenses
                             [:license
                              [:name "Eclipse Public License v2.0"]
                              [:url "https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.html"]
                              [:distribution "repo"]]]]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install
  "Installs pom and library jar in local maven repository"
  [_]
  (jar nil)
  (println "Installing" jar-file)
  (b/install {:basis     jar-basis
              :lib       lib
              :class-dir class-dir
              :version   version
              :jar-file  jar-file}))


(defn deploy
  "Deploy library to clojars.
  Environment variables CLOJARS_USERNAME and CLOJARS_PASSWORD must be set."
  [_]
  (println "Deploying" jar-file)
  (clean nil)
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact  jar-file
              :pom-file  (b/pom-path {:lib       lib
                                      :class-dir class-dir})}))
(defn uber
  "Build an executable uberjar file for codelists HTTP server"
  [_]
  (println "Building uberjar:" uber-file)
  (clean nil)
  (b/copy-dir {:src-dirs   ["src" "cmd" "resources"]
               :target-dir class-dir})
  (b/compile-clj {:basis      uber-basis
                  :src-dirs   ["src" "cmd"]
                  :ns-compile ['com.eldrix.codelists.cmd]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     uber-basis
           :main      'com.eldrix.codelists.cmd}))

(defn release
  "Deploy release to GitHub. Requires valid token in GITHUB_TOKEN environmental
   variable."
  [_]
  (uber nil)
  (println "Deploying release to github")
  (gh/release-artifact github))