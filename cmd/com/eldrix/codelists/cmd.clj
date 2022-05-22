(ns com.eldrix.codelists.cmd
  (:gen-class)
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [com.eldrix.codelists.core :as codelists]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [io.pedestal.http :as http]
            [io.pedestal.http.content-negotiation :as conneg]
            [io.pedestal.http.route :as route]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.interceptor.error :as intc-err])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

(def supported-types ["application/json" "application/edn"])
(def content-neg-intc (conneg/negotiate-content supported-types))

(defn response [status body & {:as headers}]
  {:status  status
   :body    body
   :headers headers})

(def ok (partial response 200))
(def not-found (partial response 404))

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "application/json"))

(defn write-local-date [^LocalDate o ^Appendable out _options]
  (.append out \")
  (.append out (.format (DateTimeFormatter/ISO_DATE) o))
  (.append out \"))

(extend LocalDate json/JSONWriter {:-write write-local-date})

(defn transform-content
  [body content-type]
  (case content-type
    "application/edn" (.getBytes (pr-str body) "UTF-8")
    "application/json" (.getBytes (json/write-str body) "UTF-8")))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave
   (fn [context]
     (if (get-in context [:response :headers "Content-Type"])
       context
       (update-in context [:response] coerce-to (accepted-type context))))})

(defn inject-env
  "A simple interceptor to inject an environment into the context."
  [env]
  {:name  ::inject-env
   :enter (fn [context] (update context :request assoc ::env env))})

(def entity-render
  "Interceptor to render an entity '(:result context)' into the response."
  {:name :entity-render
   :leave
   (fn [context]
     (if-let [item (:result context)]
       (assoc context :response (ok item))
       context))})


(def expand
  {:name  ::expand
   :enter (fn [ctx]
            (let [s (get-in ctx [:request :params :s])
                  env (get-in ctx [:request ::env])]
              (log/debug "expanding " s)
              (when-not (str/blank? s)
                (assoc ctx :result (codelists/realize-concepts env (codelists/parse-json s))))))})

(def status
  {:name  ::status
   :enter (fn [ctx]
            (let [{::keys [status]} (get-in ctx [:request ::env])]
              (assoc ctx :result status)))})

(def common-routes [coerce-body content-neg-intc entity-render])

(def service-error-handler
  (intc-err/error-dispatch
    [context err]

    [{:exception-type :java.lang.Exception :interceptor ::expand}]
    (assoc context :response {:status 400 :body (str "invalid parameters: " (ex-message (:exception (ex-data err))))})

    :else
    (assoc context :io.pedestal.interceptor.chain/error err)))

(def routes
  (route/expand-routes
    #{["/v1/codelists/expand" :get (conj common-routes service-error-handler expand)]
      ["/v1/codelists/status" :get (conj common-routes status)]}))

(def service-map
  {::http/routes routes
   ::http/type   :jetty
   ::http/port   8080})

(defn start-server
  "Start a codelists HTTP server.
  Parameters:
  - port            : (optional) port to use, default 8080
  - bind-address    : (optional) bind address
  - allowed-origins : (optional) a sequence of strings of hostnames or function
  - join?           : whether to join server thread or return"
  ([{:com.eldrix/keys [hermes dmd] :as env} {:keys [port bind-address allowed-origins join?] :as opts :or {join? true}}]
   (Thread/setDefaultUncaughtExceptionHandler
     (reify Thread$UncaughtExceptionHandler
       (uncaughtException [_ thread ex]
         (log/error ex "Uncaught exception on" (.getName thread)))))
   (let [cfg (cond-> {}
                     port (assoc ::http/port port)
                     bind-address (assoc ::http/host bind-address)
                     allowed-origins (assoc ::http/allowed-origins allowed-origins))]
     (-> (merge service-map cfg)
         (assoc ::http/join? join?)
         (http/default-interceptors)
         (update ::http/interceptors conj (intc/interceptor (inject-env env)))
         http/create-server
         http/start))))

(defn stop-server [server]
  (http/stop server))

;; For interactive development
(defonce server (atom nil))

(defn start-dev [svc port]
  (reset! server
          (start-server svc {:port port :join? false})))

(defn stop-dev []
  (http/stop @server))

(defn serve [{:keys [hermes dmd _port _bind-address allowed-origins] :as params} _]
  (if (and hermes dmd)
    (let [hermes' (hermes/open hermes)
          dmd' (dmd/open-store dmd)
          allowed-origins' (when allowed-origins (str/split allowed-origins #","))
          params' (cond (= ["*"] allowed-origins') (assoc params :allowed-origins (constantly true))
                        (seq allowed-origins') (assoc params :allowed-origins allowed-origins')
                        :else params)
          status {:hermes (map :term (hermes/get-release-information hermes'))
                  :dmd    {:releaseDate (dmd/fetch-release-date dmd')}}]
      (log/info "starting codelists server " params')
      (start-server {:com.eldrix/hermes hermes'
                     :com.eldrix/dmd    dmd'
                     ::status           status} params'))
    (log/error "Both hermes and dmd database directories must be specified." params)))

(def cli-options
  [["-p" "--port PORT" "Port number"
    :default 8080
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65536"]]

   ["-a" "--bind-address BIND_ADDRESS" "Address to bind"]

   [nil "--allowed-origins \"*\" or ORIGINS" "Set CORS policy, with \"*\" or comma-delimited hostnames"]

   [nil "--hermes PATH" "Path to hermes database directory"
    :validate [string? "Missing hermes database path"]]

   [nil "--dmd PATH" "Path to dmd database directory"
    :validate [string? "Missing hermes database path"]]

   ["-h" "--help"]])

(defn usage [options-summary]
  (->>
    ["Usage: codelists [options] command [parameters]"
     ""
     "Options:"
     options-summary
     ""
     "Commands:"
     " serve                      Start a codelists server"]
    (str/join \newline)))

(def commands
  {"serve" {:fn serve}})

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn invoke-command [cmd opts args]
  (if-let [f (:fn cmd)]
    (f opts args)
    (exit 1 "error: not implemented")))

(defn -main [& args]
  (let [{:keys [options arguments summary errors]} (cli/parse-opts args cli-options)
        command (get commands ((fnil str/lower-case "") (first arguments)))]
    (cond
      ;; asking for help?
      (:help options)
      (println (usage summary))
      ;; if we have any errors, exit with error message(s)
      errors
      (exit 1 (str/join \newline errors))
      ;; if we have no command, exit with error message
      (not command)
      (exit 1 (str "Invalid command\n" (usage summary)))
      ;; invoke command
      :else (invoke-command command options (rest arguments)))))

(comment)

