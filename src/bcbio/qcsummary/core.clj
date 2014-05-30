(ns bcbio.qcsummary.core
  (:require [bcbio.rnaseq.config :as config]
            [bcbio.rnaseq.util :as util]
            [clojure.java.io :as io]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [clostache.parser :as stache]
            [incanter.core :as ic]
            [me.raynes.fs :as fs]))

(defn knit-file [rmd-file]
  (let [setwd (str "setwd('" (util/dirname rmd-file) "');")
        cmd (str "library(knitr); knit('" rmd-file "')")]
    (sh "Rscript" "-e" (str setwd "library(knitr); knit('" rmd-file "')"))))


(def summary-template "bcbio/qc-summary.template")

(defn write-template [template hashmap out-dir extension]
  (let [rfile (util/change-extension (util/swap-directory template out-dir)
                                     ".Rmd")]
    (spit rfile (stache/render-resource template hashmap))
    rfile))

(defn make-Rmd-summary [summary-csv]
  (let [out-dir (util/dirname summary-csv)
        summary-config {:summary-csv (util/escape-quote summary-csv)
                        :out-dir (util/escape-quote (util/dirname summary-csv))
                        :counts-file (->> "combined.counts"
                                          (fs/file out-dir)
                                          str
                                          util/escape-quote)}]
    (write-template summary-template summary-config out-dir ".Rmd")))

(defn load-summary [fn]
  (config/load-yaml fn))

(defn summary [sample] (get-in sample [:summary :metrics]))

(defn metadata [sample] (:metadata sample))

(defn tidy-summary [summary]
  "tidy a set of summary statistics"
  (let [df (ic/to-dataset summary)]
    (ic/col-names df (map name (ic/col-names df)))))

(defn load-tidy-summary [fn]
  "load the summaries from a bcbio project file"
  (let [summary (->> fn load-summary :samples (map summary) tidy-summary)
        metadata (->> fn load-summary :samples (map :metadata) util/fix-missing-keys
                      tidy-summary)]
    (ic/conj-cols summary metadata)))

(defn write-tidy-summary [fn]
  "from a bcbio project file write a tidy version of the
   summary data as a CSV file"
  (let [out-dir (util/dirname fn)
        out-file (-> fn fs/split-ext first (str ".csv"))
        out-path (-> out-dir (io/file out-file) (.getPath))]
    (ic/save (load-tidy-summary fn) out-path)
    out-path))

(defn usage [options-summary]
  (->> [
        ""
        "Usage: bcbio-rnaseq summarize bcbio-project-file.yaml"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(def options
  [["-h" "--help"]])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn summarize-cli [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args options)]
    (cond
     (:help options) (exit 0 (usage summary))
     (not= (count arguments) 1) (exit 1 (usage summary)))
    (knit-file (make-Rmd-summary (write-tidy-summary (first arguments))))))
