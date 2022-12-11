;; copyright (c) 2021-2022 sean corfield, all rights reserved.

(ns org.corfield.build
  "Common build utilities.

  The following high-level defaults are provided:

  :target    \"target\",
  :basis     (create-basis {:project \"deps.edn\"},
  :class-dir (str target \"/classes\"),
  :jar-file  (format \"%s/%s-%s.jar\" target lib version),
  :uber-file (format \"%s/%s-%s.jar\" target lib version)
             or, if :version is not provided:
             (format \"%s/%s-standalone.jar\" target lib)

  You are expected to provide :lib and :version as needed.

  The following build task functions are provided, with the
  specified required and optional hash map options:

  clean     -- opt :target,
  deploy    -- req :lib, :version
               opt :target, :class-dir, :jar-file
               (see docstring for additional options)
  install   -- req :lib, :version
               opt :target, :class-dir, :basis, :jar-file
               (see docstring for additional options)
  jar       -- req :lib, :version
               opt :target, :class-dir, :basis, :scm, :src-dirs,
                   :resource-dirs, :tag, :jar-file
               (see docstring for additional options)
  uber      -- req :lib or :uber-file
               opt :target, :class-dir, :basis, :scm, :src-dirs,
                   :resource-dirs, :tag, :version
               (see docstring for additional options)
  run-task  -- [opts aliases]
               opt :java-opts -- defaults to :jvm-opts from aliases
                   :jvm-opts  -- added to :java-opts
                   :main      -- defaults to clojure.main
                   :main-args -- defaults to :main-opts from aliases
                   :main-opts -- added to :main-args
  run-tests -- opt :aliases (plus run-task options)
               invokes (run-task opts (into [:test] aliases))

  All of the above return the opts hash map they were passed
  (unlike some of the functions in clojure.tools.build.api).

  The following low-level defaults are also provided to make
  it easier to call the task functions here:

  :ns-compile if :main is provided and :sort is not, this
              defaults to the :main namespace (class),

  :scm        if :tag is provided, that is used here, else
              if :version is provided, that is used for :tag
              here with \"v\" prefixed,

  :src-dirs   [\"src\"]

  :src+dirs   this is a synthetic option that is used for the
              file/directory copying that is part of `jar` and
              `uber` and it is computed as :src-dirs plus
              :resource-dirs, essentially, with the former
              defaulted as noted above and the latter defaulted
              to [\"resources\"] just for the copying but otherwise
              has no default (for `tools.build/write-pom`)."
  (:refer-clojure :exclude [test])
  (:require [cawdy.core :as cawdy]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.build.api :as b]
            [clojure.tools.deps.alpha :as t]
            [org.corfield.log4j2-conflict-handler
             :refer [log4j2-conflict-handler]]))

(defn default-target
  "Return the default target directory name."
  {:arglists '([])}
  ([] (default-target nil))
  ([target]
   (or target "target")))

(defn default-basis
  "Return the default basis."
  {:arglists '([])}
  ([] (default-basis nil))
  ([basis]
   (or basis (b/create-basis {}))))

(defn default-class-dir
  "Return the default `class-dir`.

  May be passed a non-default target directory name."
  {:arglists '([] [target])}
  ([] (default-class-dir nil nil))
  ([target] (default-class-dir nil target))
  ([class-dir target]
   (or class-dir (str (default-target target) "/classes"))))

(defn default-jar-file
  "Given the `lib` and `version`, return the default JAR
  filename.

  `lib` can be omitted and will default to `'application`
  (for uberjar usage).

  May be passed a non-default target directory name."
  ([version] (default-jar-file nil nil version))
  ([lib version] (default-jar-file nil lib version))
  ([target lib version]
   (format "%s/%s-%s.jar" (default-target target) (name (or lib 'application)) version)))

(defn clean
  "Remove the target folder."
  [{:keys [target] :as opts}]
  (println "\nCleaning target...")
  (b/delete {:path (default-target target)})
  opts)

(defn- lifted-basis
  "This creates a basis where source deps have their primary
  external dependencies lifted to the top-level, such as is
  needed by Polylith and possibly other monorepo setups."
  []
  (let [default-libs  (:libs (b/create-basis))
        source-dep?   #(not (:mvn/version (get default-libs %)))
        lifted-deps
        (reduce-kv (fn [deps lib {:keys [dependents] :as coords}]
                     (if (and (contains? coords :mvn/version) (some source-dep? dependents))
                       (assoc deps lib (select-keys coords [:mvn/version :exclusions]))
                       deps))
                   {}
                   default-libs)]
    (-> (b/create-basis {:extra {:deps lifted-deps}})
        (update :libs #(into {} (filter (comp :mvn/version val)) %)))))

(defn- jar-opts
  "Provide sane defaults for jar/uber tasks.

  :lib is required, :version is optional for uber, everything
  else is optional."
  [{:keys [basis class-dir conflict-handlers jar-file lib
           main ns-compile resource-dirs scm sort src-dirs tag
           target transitive uber-file version]
    :as   opts}]
  (when transitive
    (assert (nil? basis) ":transitive cannot be true when :basis is provided"))
  (let [basis         (if transitive
                        (lifted-basis)
                        (default-basis basis))
        directory?    #(let [f (java.io.File. %)]
                         (and (.exists f) (.isDirectory f)))
        scm-default   (cond tag     {:tag tag}
                            version {:tag (str "v" version)})
        src-default   (or src-dirs ["src"])
        version       (or version "standalone")
        xxx-file      (default-jar-file target lib version)]
    (assoc opts
           :basis      (default-basis basis)
           :class-dir  (default-class-dir class-dir target)
           :conflict-handlers
           (merge log4j2-conflict-handler conflict-handlers)
           :jar-file   (or    jar-file    xxx-file)
           :ns-compile (or    ns-compile  (when (and main (not sort))
                                            [main]))
           :scm        (merge scm-default scm)
           :src-dirs   src-default
           :src+dirs   (if transitive
                         (filter directory? (:classpath-roots basis))
                         (into src-default
                               (or resource-dirs ["resources"])))
           :uber-file  (or    uber-file   xxx-file))))

(defn _jar
  "Build the library JAR file.

  Requires: :lib, :version

  Accepts any options that are accepted by:
  * tools.build/write-pom
  * tools.build/jar

  Writes pom.xml into META-INF in the :class-dir, then
  copies :src-dirs + :resource-dirs into :class-dir, then
  builds :jar-file into :target (directory).

  If you are building a JAR in a monorepo and rely on
  :local/root dependencies for the actual source components,
  such as in a Polylith project, pass :transitive true to
  use a 'lifted' basis and to ensure all source files are
  copied into the JAR."
  {:arglists '([{:keys [lib version
                        basis class-dir jar-file main manifest repos
                        resource-dirs scm src-dirs src-pom tag target
                        transitive]}])}
  [{:keys [lib version] :as opts}]
  (assert (and lib version) "lib and version are required for jar")
  (let [{:keys [class-dir jar-file src+dirs] :as opts}
        (jar-opts opts)
        current-dir (System/getProperty "user.dir")
        current-rel #(str/replace % (str current-dir "/") "")]
    (println "\nWriting pom.xml...")
    (b/write-pom opts)
    (println "Copying" (str (str/join ", " (map current-rel src+dirs)) "..."))
    (b/copy-dir {:src-dirs   src+dirs
                 :target-dir class-dir})
    (println "Building jar" (str jar-file "..."))
    (b/jar opts))
  opts)

(defn- find-java []
  (let [java-cmd (or (System/getenv "JAVA_CMD")
                     (when-let [home (System/getenv "JAVA_HOME")]
                       (str home "/bin/java")))]
    (if (and java-cmd (.exists (io/file java-cmd)))
      java-cmd
      "java")))

(defn _uber
  "Build the application uber JAR file.

  Requires: :lib or :uber-file

  Accepts any options that are accepted by:
  * `tools.build/write-pom`
  * `tools.build/compile-clj`
  * `tools.build/uber`

  The uber JAR filename is derived from :lib
  and :version if provided, else from :uber-file.

  If :version is provided, writes pom.xml into
  META-INF in the :class-dir, then

  Compiles :src-dirs into :class-dir, then
  copies :src-dirs and :resource-dirs into :class-dir, then
  builds :uber-file into :target (directory)."
  {:argslists '([{:keys [lib uber-file
                         basis class-dir compile-opts filter-nses
                         ns-compile repos resource-dirs scm sort
                         src-dirs src-pom tag target version]}])}
  [{:keys [lib uber-file] :as opts}]
  (assert (or lib uber-file) ":lib or :uber-file is required for uber")
  (let [{:keys [class-dir java-cmd lib ns-compile sort src-dirs src+dirs uber-file version]
         :as   opts}
        (jar-opts opts)]
    (if (and lib version)
      (do
        (println "\nWriting pom.xml...")
        (b/write-pom opts))
      (println "\nSkipping pom.xml because :lib and/or :version were omitted..."))
    (println "Copying" (str (str/join ", " src+dirs) "..."))
    (b/copy-dir {:src-dirs   src+dirs
                 :target-dir class-dir})
    (if (or ns-compile sort)
      (do
        (println "Compiling" (str (str/join ", " (or ns-compile src-dirs)) "..."))
        (b/compile-clj (assoc opts :java-cmd (or java-cmd (find-java)))))
      (println "Skipping compilation because :main, :ns-compile, and :sort were omitted..."))
    (println "Building uberjar" (str uber-file "..."))
    (b/uber opts))
  opts)

(defn _install
  "Install the JAR to the local Maven repo cache.

  Requires: :lib, :version

  Accepts any options that are accepted by:
  * `tools.build/install`"
  {:arglists '([{:keys [lib version
                        basis class-dir classifier jar-file target]}])}
  [{:keys [lib version basis class-dir classifier jar-file target] :as opts}]
  (assert (and lib version) ":lib and :version are required for install")
  (let [target (default-target target)]
    (b/install {:basis      (default-basis basis)
                :lib        lib
                :classifier classifier
                :version    version
                :jar-file   (or jar-file (default-jar-file target lib version))
                :class-dir  (default-class-dir class-dir target)})
    opts))

(defn _deploy
  "Deploy the JAR to Clojars.

  Requires: :lib, :version

  Accepts any options that are accepted by:
  * `deps-deploy/deploy`

  If :artifact is provided, it will be used for the deploy,
  else :jar-file will be used (making it easy to thread
  options through `jar` and `deploy`, specifying just :jar-file
  or relying on the default value computed for :jar-file)."
  {:arglists '([{:keys [lib version
                        artifact class-dir installer jar-file pom-file target]}])}
  [{:keys [lib version class-dir installer jar-file target] :as opts}]
  (assert (and lib version) ":lib and :version are required for deploy")
  (when (and installer (not= :remote installer))
    (println ":installer" installer "is deprecated -- use install task for local deployment"))
  (let [target    (default-target target)
        class-dir (default-class-dir class-dir target)
        jar-file  (or jar-file (default-jar-file target lib version))
        dd-deploy (try (requiring-resolve 'deps-deploy.deps-deploy/deploy) (catch Throwable _))]
    (if dd-deploy
      (dd-deploy (merge {:installer :remote :artifact (b/resolve-path jar-file)
                         :pom-file (b/pom-path {:lib lib :class-dir class-dir})}
                        opts))
      (throw (ex-info "deps-deploy is not available in the 'slim' build-clj" {}))))
  opts)

(defn run-task
  "Run a task based on aliases.

  If :main-args is not provided and no :main-opts are found
  in the aliases, default to the Cognitect Labs' test-runner.

  `additional-aliases` can be passed and they'll be merged to the ones of the task.
  The dev alias is not added when using something like:
  `clojure -T:dev:build org.corfield.build/xxx`

  User `deps.edn` (linked to Practicalli) is also included for additional aliases."
  [{:keys [java-cmd java-opts jvm-opts main main-args main-opts additional-aliases] :as opts} aliases]
  (println "additional-aliases=" additional-aliases)
  (let [final-aliases (into aliases additional-aliases)
        _ (println "final-aliases" final-aliases)
        task     (str/join ", " (map name final-aliases))
        _        (println "\nRunning task for:" task)
        basis    (b/create-basis {:user (str (System/getenv "XDG_CONFIG_HOME") "/clojure/deps.edn")
                                  :project "deps.edn"
                                  :aliases final-aliases})
        combined (t/combine-aliases basis final-aliases)
        cmds     (b/java-command
                  {:basis     basis
                   :java-cmd  (or java-cmd (find-java))
                   :java-opts (into (or java-opts (:jvm-opts combined))
                                    jvm-opts)
                   :main      (or 'clojure.main main)
                   :main-args (into (or main-args
                                        (:main-opts combined)
                                        ["-m" "cognitect.test-runner"])
                                    main-opts)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit)
      (throw (ex-info (str "Task failed for: " task) {}))))
  opts)

(defn run-tests
  "Run tests.

  Always adds :test to the aliases."
  [{:keys [aliases] :as opts}]
  (-> opts (run-task (into [:test] aliases))))

(defn print-doc [opts fn]
  (println (->> fn meta :doc str/split-lines (map str/trim) (str/join \newline)))
  opts)

(defn test "Run the tests. The kaocha cloverage plugin will be triggered if set in `tests.edn`." [opts]
  (-> opts
      (print-doc #'test)
      (run-task [:test/kaocha])))

(defn test-watch "Run the tests and watch. The kaocha cloverage plugin cannot be used here." [opts]
  (println "opts=" opts)
  (-> opts
      (print-doc #'test-watch)
      (run-task [:test/kaocha-watch])))

(defn eastwood "Run Eastwood." [opts]
  (-> opts
      (print-doc #'eastwood)
      (run-task [:lint/eastwood])))

(defn coverage "Run the code coverage" [opts]
  (-> opts
      (print-doc #'coverage)
      (run-task [:test/cloverage])))

(defn jar "Build the JAR" [opts]
  (-> opts
      (clean)
      (print-doc #'jar)
      (_jar)))

(defn uber "Build the uber JAR." [opts]
  (-> opts
      (assoc :main (:main-ns opts)
             :uber-file (format "target/%s-%s-standalone.jar" (name (:lib opts)) (:version opts)))
      (dissoc :lib) ;; otherwise uberfile is not named correctly
      (print-doc #'uber)
      (_uber)))

(defn install "Install the JAR locally." [opts]
  (-> opts
      jar
      (print-doc #'install)
      (_install)))

(defn ci "Run the full CI pipeline." [opts]
  (-> opts
      (print-doc #'ci)
      (test)
      (jar)
      (_install)
      (uber)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (-> opts
      (jar)
      (print-doc #'deploy)
      (_deploy)))

(defn codox
  "TODO replace/complete by funcool/codeina ? or autodoc?"
  [opts]
  (let [basis (b/create-basis {:extra '{:deps {codox/codox {:mvn/version "0.10.8"}
                                               codox-theme-rdash/codox-theme-rdash {:mvn/version "0.1.2"}}}
                               ;; This is needed because some of the namespaces
                               ;; rely on optional dependencies provided by :dev
                               :aliases [:dev]})
        expression `(do
                      ((requiring-resolve 'codox.main/generate-docs)
                       {:metadata {:doc/format :markdown}
                        :themes [:rdash]
                        :source-paths  ["src"]
                        :output-path "doc"
                        :name ~(str (:lib opts))
                        :version ~(:version opts)
                        :description "@todo description"})
                      nil)
        process-params (b/java-command
                        {:basis basis
                         :main "clojure.main"
                         :main-args ["--eval" (pr-str expression)]})]
    (b/process process-params))
  opts)

(defn caddy
  "View the codox documentation in a browser.
   Run caddy server in a separate shell:
   `/usr/bin/caddy start --config $CLJ/build-clj/resources/Caddyfile`
   Launch this build task: `clojure_caddy <specific/path>`
   Then go to <https://localhost:2015>."
  [{:keys [caddy-path] :or {caddy-path "doc"} :as opts}]
  (print-doc opts #'caddy)
  ;; add server and route
  (let [conn (cawdy/connect "http://localhost:2019")]
    (cawdy/create-server conn :project-documentation
                         {:listen [":2015"]
                          :automatic_https {:disable false}})
    (cawdy/add-route conn
                     :project-documentation "localhost"
                     :files {:root (.getAbsolutePath (io/file caddy-path))})
    (println "Please enjoy documentation at https://localhost:2015")))

(comment
  (let [conn (cawdy/connect "http://localhost:2019")]
    (cawdy/config conn)
    (cawdy/clean-config conn)))