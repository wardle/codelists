(ns com.eldrix.codelists.cmd
  (:gen-class)
  (:require [clojure.core.match :as match]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.tools.cli :as cli]
            [clojure.tools.logging :as log]
            [com.eldrix.codelists.core :as codelists]
            [com.eldrix.dmd.core :as dmd]
            [com.eldrix.hermes.core :as hermes]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.content-negotiation :as conneg]
            [io.pedestal.http.cors :as cors]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.route :as route]
            [io.pedestal.http.secure-headers :as sec-headers]
            [io.pedestal.interceptor :as intc]
            [io.pedestal.service.interceptors :as interceptors])
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
  (intc/interceptor
    {:name ::coerce-body
     :leave
     (fn [context]
       (if (get-in context [:response :headers "Content-Type"])
         context
         (update-in context [:response] coerce-to (accepted-type context))))}))

(defn inject-env
  "A simple interceptor to inject an environment into the context."
  [env]
  (intc/interceptor
    {:name  ::inject-env
     :enter (fn [context] (update context :request assoc ::env env))}))

(def entity-render
  "Interceptor to render an entity '(:result context)' into the response."
  (intc/interceptor
    {:name :entity-render
     :leave
     (fn [context]
       (if-let [item (:result context)]
         (assoc context :response (ok item))
         context))}))

(def as-lookup
  {"names" (fn [{:com.eldrix/keys [hermes]} codes]
             (map #(hash-map :id % :term (:term (hermes/get-preferred-synonym hermes % "en-GB"))) codes))})

(def expand
  (intc/interceptor
    {:name  ::expand
     :enter (fn [ctx]
              (let [s (get-in ctx [:request :params :s])
                    as (get-in ctx [:request :params :as])
                    env (get-in ctx [:request ::env])]
                (when-not (str/blank? s)
                  (let [concept-ids (codelists/realize-concepts env (codelists/parse-json s))]
                    (if (str/blank? as)
                      (assoc ctx :result concept-ids)
                      (when-let [as-f (get as-lookup (str/lower-case as))]
                        (assoc ctx :result (as-f env concept-ids))))))))}))

(def status
  (intc/interceptor
    {:name  ::status
     :enter (fn [ctx]
              (let [{::keys [status]} (get-in ctx [:request ::env])]
                (assoc ctx :result status)))}))

(def service-error-handler
  (intc/interceptor
    {:name  ::error
     :error (fn [ctx err]
              (match/match [(ex-data err)]
                [{:exception-type :java.lang.Exception :interceptor ::expand}]
                (assoc ctx :response {:status 400 :body (str "invalid parameters: " (ex-message (ex-cause err)))})

                :else
                (assoc ctx :io.pedestal.interceptor.chain/error err)))}))

(def routes
  #{["/v1/codelists/expand" :get expand]
    ["/v1/codelists/status" :get status]})

(defn create-connector
  "Create a codelists HTTP connector.
  Parameters:
  - env             : environment map with hermes and dmd
  - port            : (optional) port to use, default 8080
  - bind-address    : (optional) bind address
  - allowed-origins : (optional) a sequence of strings of hostnames or function
  - join?           : whether to join server thread or return"
  [env {:keys [port bind-address allowed-origins join?]
        :or   {port 8080 join? true bind-address "localhost"}}]
  (-> (conn/default-connector-map bind-address port)
      (conn/optionally-with-dev-mode-interceptors)
      (conn/with-interceptors
        [(cors/allow-origin allowed-origins)
         interceptors/not-found
         (inject-env env)
         route/query-params
         route/path-params-decoder
         (sec-headers/secure-headers {})
         coerce-body
         service-error-handler
         content-neg-intc
         entity-render])
      (conn/with-routes routes)
      (jetty/create-connector {:join? join?})))

(defn start! [env config]
  (conn/start! (create-connector env config)))

(defn stop! [connector]
  (conn/stop! connector))

;; For interactive development
(defonce server (atom nil))

(defn start-dev [svc port]
  (reset! server
          (start! svc {:port port :join? false})))

(defn stop-dev []
  (stop! @server))

(defn serve [{:keys [hermes dmd _port _bind-address allowed-origins] :as params} _]
  (if (and hermes dmd)
    (let [hermes' (hermes/open hermes)
          dmd' (dmd/open-store dmd)
          allowed-origins' (when allowed-origins (str/split allowed-origins #","))
          params' (cond (= ["*"] allowed-origins') (assoc params :allowed-origins (constantly true))
                        (seq allowed-origins') (assoc params :allowed-origins allowed-origins')
                        :else params)
          status {:hermes (map :term (hermes/release-information hermes'))
                  :dmd    {:releaseDate (dmd/fetch-release-date dmd')}}]
      (log/info "starting codelists server " params')
      (start! {:com.eldrix/hermes hermes'
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
