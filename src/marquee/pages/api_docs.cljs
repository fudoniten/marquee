(ns marquee.pages.api-docs
  "Browsable OpenAPI documentation for the configured backend services.

  Specs are fetched straight from the BFF (`/api/<service>/openapi.json`) — the
  same rewritten specs martian uses — and rendered as a filterable list of
  operations grouped by tag, plus the component schemas."
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [marquee.events :as events]
            [marquee.subs :as subs]
            [marquee.components.button :refer [button]]
            [marquee.lib.utils :refer [cn]]))

;; Mirrors the service keys in server/config.clj and api.cljs.
(def services
  [{:id :pseudovision     :label "Pseudovision"}
   {:id :tunarr-scheduler :label "Tunarr Scheduler"}
   {:id :tunabrain        :label "Tunabrain"}])

(def ^:private http-methods
  ["get" "post" "put" "patch" "delete" "head" "options"])

;; Full literal class strings so Tailwind's content scanner keeps them.
(def ^:private method-classes
  {"get"     "bg-blue-100 text-blue-800 border-blue-200"
   "post"    "bg-green-100 text-green-800 border-green-200"
   "put"     "bg-amber-100 text-amber-800 border-amber-200"
   "patch"   "bg-yellow-100 text-yellow-800 border-yellow-200"
   "delete"  "bg-red-100 text-red-800 border-red-200"
   "head"    "bg-gray-100 text-gray-800 border-gray-200"
   "options" "bg-gray-100 text-gray-800 border-gray-200"})

(defn- status-class [status]
  (case (first (str status))
    \2 "bg-green-100 text-green-800"
    \3 "bg-blue-100 text-blue-800"
    \4 "bg-amber-100 text-amber-800"
    \5 "bg-red-100 text-red-800"
    "bg-gray-100 text-gray-800"))

;; --- schema rendering -------------------------------------------------------

(defn- ref-name [ref]
  (last (str/split ref #"/")))

(defn- schema-summary
  "A short, one-line description of a schema's shape."
  [schema]
  (cond
    (nil? schema)                  "any"
    (get schema "$ref")            (ref-name (get schema "$ref"))
    (get schema "enum")            (str "enum: " (str/join " | " (map pr-str (get schema "enum"))))
    (= "array" (get schema "type")) (str "array<" (schema-summary (get schema "items")) ">")
    (get schema "type")            (str (get schema "type")
                                        (when-let [f (get schema "format")] (str " (" f ")")))
    (get schema "oneOf")           "oneOf"
    (get schema "anyOf")           "anyOf"
    (get schema "allOf")           "allOf"
    :else                          "object"))

(defn- resolve-ref [schemas schema]
  (if-let [ref (get schema "$ref")]
    (get schemas (ref-name ref))
    schema))

(defn- properties-table [schema]
  (let [props    (get schema "properties")
        required (set (get schema "required"))]
    (when (seq props)
      [:div {:class "overflow-x-auto"}
       [:table {:class "w-full text-sm border-collapse"}
        [:tbody
        (for [[pname pschema] (sort-by key props)]
          ^{:key pname}
          [:tr {:class "border-b border-border align-top"}
           [:td {:class "py-1 pr-4 font-mono whitespace-nowrap"}
            pname
            (when (required pname) [:span {:class "text-red-500 ml-0.5"} "*"])]
           [:td {:class "py-1 pr-4 text-muted-foreground font-mono whitespace-nowrap"}
            (schema-summary pschema)]
           [:td {:class "py-1 text-muted-foreground"}
            (get pschema "description")]])]]])))

(defn- schema-view
  "Renders a schema in a bordered block: a property table for objects, a type
  summary otherwise. Resolves a single level of `$ref`."
  [schemas schema]
  (let [resolved (resolve-ref schemas schema)]
    [:div {:class "rounded-md border bg-muted p-3"}
     (when-let [ref (get schema "$ref")]
       [:div {:class "text-xs font-mono text-muted-foreground mb-2"} (ref-name ref)])
     (if (and resolved (get resolved "properties"))
       [properties-table resolved]
       [:div {:class "text-sm font-mono text-muted-foreground"}
        (schema-summary (or resolved schema))])]))

;; --- operation rendering ----------------------------------------------------

(defn- param-type [param]
  (if-let [s (get param "schema")]
    (schema-summary s)
    (or (get param "type") "")))

(defn- parameters-table [params]
  (when (seq params)
    [:div
     [:h4 {:class "text-sm font-semibold mb-2"} "Parameters"]
     [:div {:class "overflow-x-auto"}
      [:table {:class "w-full text-sm border-collapse"}
       [:thead
       [:tr {:class "text-left text-muted-foreground border-b"}
        [:th {:class "py-1 pr-4 font-medium"} "Name"]
        [:th {:class "py-1 pr-4 font-medium"} "In"]
        [:th {:class "py-1 pr-4 font-medium"} "Type"]
        [:th {:class "py-1 font-medium"} "Description"]]]
      [:tbody
       (for [param params]
         ^{:key (str (get param "in") "-" (get param "name"))}
         [:tr {:class "border-b border-border align-top"}
          [:td {:class "py-1 pr-4 font-mono whitespace-nowrap"}
           (get param "name")
           (when (get param "required") [:span {:class "text-red-500 ml-0.5"} "*"])]
          [:td {:class "py-1 pr-4 text-muted-foreground"} (get param "in")]
          [:td {:class "py-1 pr-4 text-muted-foreground font-mono whitespace-nowrap"} (param-type param)]
          [:td {:class "py-1 text-muted-foreground"} (get param "description")]])]]]]))

(defn- request-body-view [schemas request-body]
  (when request-body
    (let [content (get request-body "content")
          schema  (or (get-in content ["application/json" "schema"])
                      (some-> content vals first (get "schema")))]
      [:div
       [:h4 {:class "text-sm font-semibold mb-2"}
        "Request Body"
        (when (get request-body "required")
          [:span {:class "text-red-500 ml-1 text-xs"} "(required)"])]
       [schema-view schemas schema]])))

(defn- responses-view [schemas responses]
  (when (seq responses)
    [:div
     [:h4 {:class "text-sm font-semibold mb-2"} "Responses"]
     [:div {:class "space-y-3"}
      (for [[status resp] (sort-by key responses)]
        ^{:key status}
        (let [content (get resp "content")
              schema  (or (get-in content ["application/json" "schema"])
                          (some-> content vals first (get "schema"))
                          (get resp "schema"))]
          [:div
           [:div {:class "flex items-baseline gap-2 mb-1"}
            [:span {:class (cn "px-2 py-0.5 rounded text-xs font-mono font-semibold" (status-class status))}
             status]
            [:span {:class "text-sm text-muted-foreground"} (get resp "description")]]
           (when schema
             [schema-view schemas schema])]))]]))

(defn- operation-row
  [schemas {:keys [path method op expanded? on-toggle]}]
  (let [op-id   (get op "operationId")
        summary (get op "summary")]
    [:div {:class "border rounded-md overflow-hidden"}
     [:button {:class "w-full flex items-center gap-3 px-3 py-2 text-left hover:bg-accent transition-colors"
               :on-click on-toggle}
      [:span {:class (cn "px-2 py-0.5 rounded text-xs font-mono font-semibold uppercase border"
                         (method-classes method))}
       method]
      [:span {:class "font-mono text-sm"} path]
      (when summary
        [:span {:class "text-sm text-muted-foreground truncate"} summary])]
     (when expanded?
       [:div {:class "px-3 py-3 border-t bg-card space-y-4"}
        (when op-id
          [:div {:class "text-xs text-muted-foreground"}
           "operationId: " [:span {:class "font-mono"} op-id]])
        (when-let [d (get op "description")]
          [:p {:class "text-sm text-muted-foreground"} d])
        [parameters-table (get op "parameters")]
        [request-body-view schemas (get op "requestBody")]
        [responses-view schemas (get op "responses")]])]))

;; --- schema browser ---------------------------------------------------------

(defn- schema-entry
  [schema {:keys [name expanded? on-toggle]}]
  [:div {:class "border rounded-md overflow-hidden"}
   [:button {:class "w-full flex items-center gap-2 px-3 py-2 text-left hover:bg-accent transition-colors"
             :on-click on-toggle}
    [:span {:class "font-mono text-sm font-medium"} name]
    [:span {:class "text-xs text-muted-foreground"} (schema-summary schema)]]
   (when expanded?
     [:div {:class "px-3 py-3 border-t bg-card"}
      (if (get schema "properties")
        [properties-table schema]
        [:div {:class "text-sm font-mono text-muted-foreground"} (schema-summary schema)])])])

;; --- spec assembly ----------------------------------------------------------

(defn- collect-operations [spec]
  (for [[path methods] (get spec "paths")
        method          http-methods
        :let            [op (get methods method)]
        :when           op]
    {:path path :method method :op op
     :tag  (or (first (get op "tags")) "default")}))

(defn- matches-filter? [text {:keys [path method op tag]}]
  (or (str/blank? text)
      (let [needle (str/lower-case text)
            hay    (str/lower-case
                    (str/join " " [path method tag
                                   (get op "summary")
                                   (get op "operationId")]))]
        (str/includes? hay needle))))

(defn- service-tabs [selected]
  [:div {:class "flex flex-wrap gap-2"}
   (for [{:keys [id label]} services]
     ^{:key id}
     [button {:variant  (if (= id selected) :secondary :ghost)
              :size     :sm
              :on-click #(rf/dispatch [::events/select-api-service id])}
      label])])

(defn- spec-body [service-id spec-entry]
  (case (:status spec-entry)
    :loading [:p {:class "text-muted-foreground"} "Loading spec…"]

    :error   [:p {:class "text-destructive"}
              (str "Failed to load spec for " (name service-id) ": " (:error spec-entry))]

    :loaded
    (let [spec        (:spec spec-entry)
          info        (get spec "info")
          schemas     (or (get-in spec ["components" "schemas"])
                          (get spec "definitions"))
          filter-text @(rf/subscribe [::subs/api-filter])
          expanded    @(rf/subscribe [::subs/api-expanded-ops])
          ops         (->> (collect-operations spec)
                           (filter #(matches-filter? filter-text %))
                           (sort-by (juxt :path :method)))
          grouped     (into (sorted-map) (group-by :tag ops))]
      [:div {:class "space-y-6"}
       (when info
         [:div
          [:div {:class "flex items-baseline gap-2"}
           [:h2 {:class "text-2xl font-semibold"} (get info "title")]
           (when-let [v (get info "version")]
             [:span {:class "text-sm text-muted-foreground font-mono"} (str "v" v)])]
          (when-let [d (get info "description")]
            [:p {:class "text-sm text-muted-foreground mt-1"} d])])

       [:input {:type        "text"
                :placeholder "Filter endpoints…"
                :value       filter-text
                :on-change   #(rf/dispatch [::events/set-api-filter (.. % -target -value)])
                :class       "flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"}]

       (if (empty? ops)
         [:p {:class "text-muted-foreground"} "No endpoints match."]
         (for [[tag tag-ops] grouped]
           ^{:key tag}
           [:div {:class "space-y-2"}
            [:h3 {:class "text-lg font-semibold border-b pb-1"} tag]
            (for [{:keys [path method] :as o} tag-ops]
              (let [op-key (str (name service-id) "|" method "|" path)]
                ^{:key op-key}
                [operation-row schemas
                 (assoc o
                        :expanded? (contains? expanded op-key)
                        :on-toggle #(rf/dispatch [::events/toggle-api-operation op-key]))]))]))

       (when (seq schemas)
         [:div {:class "space-y-2 pt-2"}
          [:h3 {:class "text-lg font-semibold border-b pb-1"} "Schemas"]
          (for [[sname schema] (sort-by key schemas)]
            (let [op-key (str (name service-id) "|schema|" sname)]
              ^{:key sname}
              [schema-entry schema
               {:name      sname
                :expanded? (contains? expanded op-key)
                :on-toggle #(rf/dispatch [::events/toggle-api-operation op-key])}]))])])

    ;; no entry yet
    [:p {:class "text-muted-foreground"} "Select a service."]))

(defn page []
  (let [selected  @(rf/subscribe [::subs/api-selected-service])
        spec-entry @(rf/subscribe [::subs/api-spec selected])]
    [:div {:class "space-y-6"}
     [:div
      [:h1 {:class "text-3xl font-bold tracking-tight"} "API Docs"]
      [:p {:class "text-muted-foreground"}
       "Browse the OpenAPI definitions of the backend services."]]
     [service-tabs selected]
     (when selected
       [spec-body selected spec-entry])]))
