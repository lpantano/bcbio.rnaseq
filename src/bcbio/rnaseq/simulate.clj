(ns bcbio.rnaseq.simulate
  (:use [bcbio.rnaseq.util]
        [bcbio.rnaseq.config]
        [bcbio.rnaseq.compare :only [compare-callers]]
        [clojure.java.shell :only [sh]]
        [clojure.tools.cli :refer [parse-opts]])
  (:require [me.raynes.fs :as fs]
            [clostache.parser :as stache]
            [clojure.string :as string]
            [bcbio.rnaseq.templates :as templates]))

(def sim-template "comparisons/simulate.template")
(def compare-template "comparisons/compare-simulated.template")

(defn sim-dir [] (str (fs/file (analysis-dir) "simulation")))

(defn simulate
  ([sim-dir]
     (let [count-file (str (fs/file sim-dir "sim.counts"))
           rfile (str (fs/file sim-dir "sim.R"))]
       (safe-makedir sim-dir)
       (spit rfile
             (stache/render-resource sim-template
                                  {:count-file (escape-quote count-file)}))
       (sh "Rscript" rfile)
       count-file))
  ([] (simulate (sim-dir))))

(defn get-analysis-template [out-dir count-file]
  {:de-out-dir out-dir
   :count-file count-file
   :comparison ["group1" "group2"]
   :conditions ["group1" "group1" "group1" "group2" "group2" "group2"]
   :condition-name "group1_vs_group2"})

(defn run-one-template [analysis-template template]
  (let [analysis-config (templates/add-out-files-to-config template analysis-template)]
    (templates/run-template template analysis-config)))

(defn compare-simulated-results [in-files]
  (let [out-file (swap-directory "roc-plot.pdf" (sim-dir))
        score-file (str (fs/file (sim-dir) "sim.scores"))
        rfile (str (fs/file (sim-dir) "compare-simulated.R"))
        template-config {:out-file (escape-quote out-file)
                         :score-file (escape-quote score-file)
                         :in-files (seq-to-rlist in-files)
                         :project (escape-quote "simulated")}]
    (spit rfile (stache/render-resource compare-template template-config))
    (apply sh ["Rscript" "--verbose" rfile])
    out-file))

(defn run-simulation []
  (let [count-file (simulate)
        analysis-template (get-analysis-template (sim-dir) count-file)
        out-files (map :out-file (map (partial run-one-template analysis-template)
                                      templates/templates))]
    (compare-callers out-files)
    (compare-simulated-results out-files)))

(def options
  [["-h" "--help"]])

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn usage [options-summary]
  (->> [
        ""
        "Usage: bcbio-rnaseq simulate [options]"
        ""
        "Options:"
        options-summary]
       (string/join \newline)))

(defn simulate-cli [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args options)]
    (cond
     (:help options) (exit 0 (usage summary)))
    (run-simulation)))
