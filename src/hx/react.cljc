(ns hx.react
  (:require #?(:cljs [goog.object :as gobj])
            #?(:cljs ["react" :as react])
            #?(:clj [hx.compiler.core])
            #?(:clj [hx.compiler.parser :as parser])
            #?(:clj [hx.react.interceptors :as interceptors])
            [hx.utils :as utils])
  (:refer-clojure :exclude [compile]))

(def ^:dynamic *interceptors* (atom []))

(defn register-interceptor! [{:keys [name enter leave] :as interceptor}]
  (swap! *interceptors* conj interceptor))

(defn collapse-interceptors []
  (reduce (fn [{:keys [enter leave]} interceptor]
            (let [new-enter (:enter interceptor)
                  new-leave (:leave interceptor)]
              {:enter (if (nil? new-enter)
                        enter
                        (fn [context]
                          (new-enter (enter context))))
               :leave (if (nil? new-leave)
                        leave
                        (fn [context]
                          (leave (new-leave context))))}))
          {:enter identity
           :leave identity}
          @*interceptors*))

#_(binding [*interceptors* (atom [])]
    (register-interceptor! {:name ::test
                            :enter #(+ 1 %)
                            :leave #(- % 1)
                            })
    (register-interceptor! {:name ::test2
                            :enter #(* 3 %)
                            :leave #(/ % 3)})
    (let [{:keys [enter leave]} (!collapse-interceptors)]
      (leave (enter 2)))
    )


(defn is-hx? [el]
  ;; TODO: detect hx component
  true)

#?(:clj (do (register-interceptor! interceptors/compile)))

(defn compile* [form]
  (let [{:keys [enter leave]} (collapse-interceptors)
        transformed (-> {:in form}
                        (enter)
                        (leave))]
    (:out transformed)))

(defmacro compile [& form]
  (let [compiled (compile* form)]
    `(do ~@compiled)))

(defmacro defcomponent
  {:style/indent [1 :form [1]]}
  [display-name constructor & body]
  (let [with-compile (compile* body)
        methods (filter #(not (:static (meta %))) with-compile)
        statics (->> (filter #(:static (meta %)) with-compile)
                     (map #(apply vector (str (munge (first %))) (rest %)))
                     (into {"displayName" (name display-name)}))
        method-names (into [] (map #(list 'quote (first %)) methods))]
    `(def ~display-name
       (let [ctor# (fn ~(second constructor)
                     ;; constructor must return `this`
                     ~@(drop 2 constructor))
             class# (hx.react/create-pure-component
                     ctor#
                     ~statics
                     ~method-names)]
         (cljs.core/specify! (.-prototype class#)
           ~'Object
           ~@methods)
         class#))))

(defmacro defnc [name props-bindings & body]
  (let [with-compile (compile* body)]
    `(defn ~name [props#]
       (let [~@props-bindings (hx.react/props->clj props#)]
         ~@with-compile))))

#?(:clj (defmethod parser/parse-element
           :<>
           [el & args]
           (parser/-parse-element
            'hx.react/fragment
            args)))


#?(:cljs (defn props->clj [props]
           (utils/shallow-js->clj props :keywordize-keys true)))

#?(:cljs (defn styles->js [props]
           (cond
             (and (map? props) (:style props))
             (assoc props :style (clj->js (:style props)))

             (gobj/containsKey props "style")
             (do (->> (gobj/get props "style")
                      (clj->js)
                      (gobj/set props "style"))
                 props)

             :default props)))

#?(:cljs (defn clj->props [props & {:keys [styles?]}]
           (-> (if styles? (styles->js props) props)
               (utils/reactify-props)
               (utils/shallow-clj->js props))))

#?(:clj (defn create-element [el p & c]
          nil)
   :cljs (defn create-element [el p & c]
           (if (or (string? p) (number? p) (react/isValidElement p))
             (apply react/createElement el nil p c)

             ;; if el is a keyword, or is not marked as an hx component,
             ;; we recursively convert styles
             (let [js-interop? (or (string? el) (not (is-hx? el)))
                   props (clj->props p :styles? js-interop?)]
               (apply react/createElement el props c)))))

#?(:cljs (defn assign-methods [class method-map]
           (doseq [[method-name method-fn] method-map]
             (gobj/set (.-prototype class)
                       (munge (name method-name))
                       method-fn))
           class))

#?(:cljs (defn create-class [super-class init-fn static-properties method-names]
           (let [ctor (fn [props]
                        (this-as this
                          ;; auto-bind methods
                          (doseq [method method-names]
                            (gobj/set this (munge method)
                                      (.bind (gobj/get this (munge method)) this)))

                          (init-fn this props)))]
             ;; set static properties on prototype
             (doseq [[k v] static-properties]
               (gobj/set ctor k v))
             (goog/inherits ctor super-class)
             ctor)))

#?(:cljs (defn create-pure-component [init-fn static-properties method-names]
           (create-class react/PureComponent init-fn static-properties method-names)))

#?(:cljs (def fragment react/Fragment))