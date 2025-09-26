(ns task-manager.core-test
  (:require [midje.sweet :refer :all]
            [task-manager.core :as core]
            [clojure.java.io :as io]))


;;  Helper 
(def test-db "target/test-tasks.json")

(defmacro with-test-db [& body]
  `(with-redefs [core/db-path test-db]
     (when (.exists (io/file test-db)) (io/delete-file test-db))
     (core/reset-db!)
     (try
       ~@body
       (finally
         (when (.exists (io/file test-db)) (io/delete-file test-db))))))

;;  VALIDACIJE / NORMALIZACIJE 

(facts "valid-title?"
       (core/valid-title? "A") => true
       (core/valid-title? "")  => false)

(facts "normalize-priority / normalize-status"
       (core/normalize-priority "HIGH")   => :high
       (core/normalize-priority :Medium)  => :medium
       (core/normalize-status   "Doing")  => :doing
       (core/normalize-status   :BLOCKED) => :blocked)

(facts "nevažeći prioritet / datum bacaju 422"
       (with-test-db
         (fact "invalid priority"
               (core/add-task! {:title "X" :priority "urgent"})
               => (throws clojure.lang.ExceptionInfo #"Invalid priority"))
         (fact "invalid date"
               (core/add-task! {:title "X" :due "2025-13-99"})
               => (throws clojure.lang.ExceptionInfo #"Invalid date"))))

;;  CREATE / READ 

(facts "add-task! postavlja default vrednosti"
       (with-test-db
         (let [t (core/add-task! {:title "Prvi"})]
           (:id t)       => 1
           (:status t)   => :todo
           (:priority t) => :medium
           (:tags t)     => vector?)))

(facts "list-tasks filtriranje po statusu/priority/tag/q/due-before"
       (with-test-db
         ;; oba su kreirana kao :todo,
         ;; odmah posle kreiranja drugog taska menjamo status na :doing
         (core/add-task! {:title "UML use-case" :priority "high" :tags ["AMSI"] :due "2020-01-01"})
         (let [t2 (core/add-task! {:title "Sekvencijski dijagram" :priority "low" :tags ["AMSI" "diagram"]})]
           (core/update-task! (:id t2) {:status "doing"}))
         (let [all   (core/list-tasks {})
               only  (core/list-tasks {:status :todo})
               hi    (core/list-tasks {:priority :high})
               tagA  (core/list-tasks {:tag "AMSI"})
               find1 (core/list-tasks {:q "dijagram"})
               dueb  (core/list-tasks {:due-before "2021-01-01"})]
           (count all)   => 2
           (count only)  => 1
           (count hi)    => 1
           (count tagA)  => 2
           (count find1) => 1
           (count dueb)  => 1)))

;;  UPDATE / COMPLETE 

(facts "update-task! parcijalno menja polja i sanitizuje tagove"
       (with-test-db
         (let [t1 (core/add-task! {:title "Spec"})
               _  (core/add-task! {:title "Implementacija"})
               u1 (core/update-task! (:id t1) {:priority "HIGH" :tags ["a" "a" "b" " " "b"]})
               u2 (core/update-task! (:id t1) {:status "doing"})]
           (:priority u1) => :high
           (:tags u1)     => ["a" "b"]
           (:status u2)   => :doing)))

(facts "complete-task! postavlja status :done"
       (with-test-db
         (let [t (core/add-task! {:title "Završi"})]
           (:status (core/complete-task! (:id t))) => :done)))

(facts "update/delete na nepostojećem id-u daju 404"
       (with-test-db
         (fact "update 404"
               (core/update-task! 999 {:title "X"})
               => (throws clojure.lang.ExceptionInfo #"Not found"))
         (fact "delete 404"
               (core/delete-task! 999)
               => (throws clojure.lang.ExceptionInfo #"Not found"))))

;;  DELETE 

(facts "delete-task! uklanja traženi task"
       (with-test-db
         (let [t1 (core/add-task! {:title "A"})
               t2 (core/add-task! {:title "B"})]
           (core/delete-task! (:id t1)) => (contains {:deleted (:id t1)})
           (map :id (:tasks (core/read-db))) => [(:id t2)])))

;;  SUMMARY 

(facts "summary računa total/by-status/by-priority i overdue"
       (with-test-db
         ;; jedan overdue (prošlost), jedan done, jedan future
         (core/add-task! {:title "Overdue" :due "2020-01-01" :priority "high"})
         (let [done (core/add-task! {:title "Gotov" :priority "low"})]
           (core/complete-task! (:id done)))
         (core/add-task! {:title "Budući" :due "2099-01-01" :priority "medium"})
         (let [s (core/summary)]
           (:total s) => 3
           (get-in s [:by-status :done]) => 1
           (get-in s [:by-priority :high]) => 1
           (:overdue s) => 1)))



