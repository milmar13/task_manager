(ns task-manager.core
  (:require
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [compojure.core :refer [defroutes GET POST]]
   [compojure.route :as route]
   [ring.adapter.jetty :refer [run-jetty]]
   [ring.util.response :as resp]
   [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.resource :refer [wrap-resource]])
  (:import [java.time LocalDate Instant]))

;; --- DataBase ---
(def db-path "src/task_manager/tasks.json")

(defn ensure-db! []
  (when-not (.exists (io/file db-path))
    (spit db-path (json/generate-string {:tasks []} {:pretty true}))))

(defn read-db []
  (ensure-db!)
  (-> (slurp db-path) (json/parse-string true)))

(defn write-db! [data]
  (spit db-path (json/generate-string data {:pretty true})))

;; --- Validacije ---
(defn now [] (str (Instant/now)))
(defn parse-date [s] (LocalDate/parse s))
(defn valid-date? [s] (try (parse-date s) true (catch Exception _ false)))
(defn valid-title? [s] (and (string? s) (<= 1 (count s) 120)))

(def valid-priorities #{:low :medium :high})
(def valid-statuses  #{:todo :doing :blocked :done})

;; --- normalizacija ---
;; --- normalizacija ---
(defn normalize-priority [p]
  (when p (-> (name p) str/trim str/lower-case keyword)))

(defn normalize-status [s]
  (when s (-> (name s) str/trim str/lower-case keyword)))


(defn valid-priority* [p] (contains? valid-priorities (normalize-priority p)))
(defn valid-status*   [s] (contains? valid-statuses  (normalize-status s)))

(defn next-id [tasks] (inc (apply max 0 (map :id tasks))))

(defn sanitize-tags [tags]
  (->> (or tags [])
       (map str) (map str/trim)
       (remove str/blank?) distinct vec))

(defn overdue? [{:keys [due status]}]
  (and due (not= status :done) (.isBefore (parse-date due) (LocalDate/now))))

;; --- Servisni sloj ---
(defn list-tasks [{:keys [status priority q due-before tag]}]
  ;; normalizuj parametre
  (let [status   (some-> status normalize-status)
        priority (some-> priority normalize-priority)
        qlc      (some-> q str/lower-case)
        tagq     (some-> tag str/trim str/lower-case)
        tasks    (:tasks (read-db))]
    (->> tasks
         (filter
          (fn [t]
            ;; normalizuj vrednosti iz taska (mogu biti stringovi iz JSON-a)
            (let [t-status   (normalize-status   (:status t))
                  t-priority (normalize-priority (:priority t))
                  tagset     (->> (:tags t)
                                  (map str)
                                  (map str/trim)
                                  (map str/lower-case)
                                  set)]
              (and
               (if status   (= status t-status) true)
               (if priority (= priority t-priority) true)
               (if qlc (or (str/includes? (str/lower-case (:title t)) qlc)
                           (str/includes? (str/lower-case (or (:desc t) "")) qlc))
                   true)
               (if tagq (contains? tagset tagq) true)
               (if due-before
                 (when-let [d (:due t)]
                   (.isBefore (parse-date d) (parse-date due-before)))
                 true))))))))


(defn add-task! [{:keys [title desc priority due tags]}]
  (let [priority* (normalize-priority priority)]
    (when-not (valid-title? title) (throw (ex-info "Invalid title" {:code 422})))
    (when (and due (not (valid-date? due))) (throw (ex-info "Invalid date" {:code 422})))
    (when (and priority (not (valid-priority* priority))) (throw (ex-info "Invalid priority" {:code 422})))
    (let [db (read-db)
          ts (:tasks db)
          task {:id (next-id ts)
                :title title
                :desc (or desc "")
                :status :todo
                :priority (or priority* :medium)
                :due due
                :tags (sanitize-tags tags)
                :created-at (now)
                :updated-at (now)}]
      (write-db! (assoc db :tasks (conj ts task)))
      task)))

(defn update-task! [id patch]
  (let [patch (-> patch
                  (update :priority normalize-priority)
                  (update :status   normalize-status))
        ;; sanitizuj tagove SAMO ako su prosleđeni u patch-u
        patch (if (contains? patch :tags)
                (update patch :tags sanitize-tags)
                patch)]
    (let [db (read-db)
          ts (:tasks db)
          t  (some #(when (= (:id %) id) %) ts)]
      (when-not t (throw (ex-info "Not found" {:code 404})))
      (when-let [ttl (:title patch)] (when-not (valid-title? ttl) (throw (ex-info "Invalid title" {:code 422}))))
      (when-let [d (:due patch)]     (when (and d (not (valid-date? d))) (throw (ex-info "Invalid date" {:code 422}))))
      (when-let [p (:priority patch)] (when-not (valid-priority* p) (throw (ex-info "Invalid priority" {:code 422}))))
      (when-let [s (:status patch)]  (when-not (valid-status* s)   (throw (ex-info "Invalid status" {:code 422}))))
      (let [nt (-> t
                   (merge patch)
                   (assoc :updated-at (now)))
            nts (mapv #(if (= (:id %) id) nt %) ts)]
        (write-db! (assoc db :tasks nts))
        nt))))


(defn complete-task! [id] (update-task! id {:status :done}))

(defn delete-task! [id]
  (let [db (read-db)
        ts (:tasks db)]
    (when-not (some #(= (:id %) id) ts) (throw (ex-info "Not found" {:code 404})))
    (write-db! (assoc db :tasks (vec (remove #(= (:id %) id) ts))))
    {:deleted id}))

(defn summary []
  (let [ts (:tasks (read-db))
        statuses (map (comp normalize-status :status) ts)
        prios    (map (comp normalize-priority :priority) ts)]
    {:total (count ts)
     :by-status (frequencies statuses)
     :by-priority (frequencies prios)
     :overdue (count (filter overdue? ts))}))


(defn reset-db! []
  (write-db! {:tasks []})
  {:message "Reset done"})

(defn safe [handler-fn]
  (try (handler-fn)
       (catch clojure.lang.ExceptionInfo e
         (let [{:keys [code]} (ex-data e)]
           (-> (resp/response {:error (.getMessage e)})
               (resp/status (or code 400)))))
       (catch Exception _
         (-> (resp/response {:error "Internal error"})
             (resp/status 500)))))

;; --- Rute ---
(defroutes routes
  ;; Health
  (GET "/health" [] (resp/response {:ok true :app "task-manager"}))
  ;; Root -> index.html
  (GET "/" [] (resp/resource-response "index.html" {:root "public"}))

  ;; List
  (GET "/tasks" {params :params}
    (let [status     (some-> (:status params) normalize-status)
          priority   (some-> (:priority params) normalize-priority)
          q          (:q params)
          due-before (:due_before params)
          tag        (:tag params)]
      (resp/response (list-tasks {:status status
                                  :priority priority
                                  :q q
                                  :due-before due-before
                                  :tag tag}))))

  ;; Create
  (POST "/tasks" {body :body}
    (safe #(-> (add-task! body) resp/response (resp/status 201))))

  ;; Update
  (POST "/tasks/:id/update" [id :as {body :body}]
    (safe #(resp/response (update-task! (Long/parseLong id) body))))

  ;; Complete
  (POST "/tasks/:id/complete" [id]
    (safe #(resp/response (complete-task! (Long/parseLong id)))))

  ;; Delete
  (POST "/tasks/:id/delete" [id]
    (safe #(resp/response (delete-task! (Long/parseLong id)))))

  ;; Summary
  (GET "/summary" [] (resp/response (summary)))

  ;; Reset DataBase
  (POST "/reset-db" [] (resp/response (reset-db!)))

  ;; static + 404
  (route/resources "/")
  (route/not-found "Not Found"))

(def app
  (-> routes
      (wrap-json-body {:keywords? true})
      wrap-json-response
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:get :post])
      (wrap-resource "public")))

(defn -main []
  (println "Server running on http://localhost:3000")
  (ensure-db!)
  (run-jetty app {:port 3000}))

