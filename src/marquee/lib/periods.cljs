(ns marquee.lib.periods
  "Calendar periods for the scheduling pipeline, in the shapes Tunarr
  Scheduler's endpoints take:
    - quarter: {:quarter \"Q4\" :year 2026 :date \"2026-10-01\" :label \"Q4 2026\"}
      (`:date` is what ?date= on POST /api/scheduling/quarterly wants; the
      grid endpoint takes ?quarter=&year=)
    - month:   {:month \"2026-11\" :date \"2026-11-01\" :label \"November 2026\"}
      (`:date` for POST /api/scheduling/monthly, `:month` for GET .../overrides)
  Dates are computed in the browser's local time zone, which is also the
  zone the operator thinks of \"this month\" in.")

(defn- pad2 [n] (if (< n 10) (str "0" n) (str n)))

(defn- month-at
  "The month `offset` months from today's (0 = this month)."
  [offset]
  (let [now (js/Date.)
        d   (js/Date. (.getFullYear now) (+ (.getMonth now) offset) 1)
        y   (.getFullYear d)
        m   (inc (.getMonth d))]
    {:month (str y "-" (pad2 m))
     :date  (str y "-" (pad2 m) "-01")
     :label (.toLocaleDateString d js/undefined #js {:month "long" :year "numeric"})}))

(defn current-month [] (month-at 0))
(defn next-month [] (month-at 1))

(defn- quarter-at
  "The quarter `offset` quarters from today's (0 = this quarter)."
  [offset]
  (let [now   (js/Date.)
        q0    (js/Math.floor (/ (.getMonth now) 3))
        d     (js/Date. (.getFullYear now) (* 3 (+ q0 offset)) 1)
        y     (.getFullYear d)
        q     (inc (js/Math.floor (/ (.getMonth d) 3)))]
    {:quarter (str "Q" q)
     :year    y
     :date    (str y "-" (pad2 (inc (* 3 (dec q)))) "-01")
     :label   (str "Q" q " " y)}))

(defn current-quarter [] (quarter-at 0))
(defn next-quarter [] (quarter-at 1))

(defn quarter-key
  "Stable string key for a quarter map, for caches and <select> values."
  [{:keys [quarter year]}]
  (str quarter "-" year))
