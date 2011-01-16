(ns temaworks.views.subject
  (:import (org.zkoss.zul
	    Window
	    Groupbox
	    Bandbox
	    Listbox Listhead Listheader Listitem
	    Paging           
	    Panel Panelchildren
	    Vbox Hbox
	    Borderlayout Center West North South
	    Grid Row Rows Columns Column
	    Label Textbox Intbox
	    Radio Radiogroup
	    Menu Menubar Menupopup Menuitem Messagebox
	    Tab Tabbox Tabs Tabpanel Tabpanels
	    Toolbar Toolbarbutton
	    Caption
	    Combobox Checkbox Comboitem
	    Button
	    Tree Treechildren Treeitem Treerow Treecell
	    Listcell
	    SimpleListModel
	    ListModelList
	    ListitemRenderer))
  (:import (java.util ArrayList))
  (:import (org.zkoss.zk.ui.event
	    EventListener Event Events InputEvent))
  (:import (org.zkoss.zk.ui WrongValueException Executions))
  (:import (org.zkoss.util.resource Labels))
  (use [clojure.contrib.seq-utils :only (positions)])
  (:use 
   [temaworks.handling aspect crud]
   [temaworks.meta types data]))

(declare gen-ref-selector gen-form gen-selector)

(defn to-class
  [s]
  (clojure.lang.Reflector/invokeConstructor
   (resolve (symbol s))
   (to-array [])))

(defrecord Widget-wrapper
  [getter setter enabler])

(defmulti to-str class)
(defmethod to-str Long [x] (Long/toString x))
(defmethod to-str Integer [x] (Integer/toString x))
(defmethod to-str Double [x] (format "%.2f" (double x)))
(defmethod to-str Float [x] (Float/toString x))
(defmethod to-str java.util.Date [x] (format "%1$td-%1$tm-%1$tY" x))
(defmethod to-str String [x] x)
(defmethod to-str Boolean [x] (if x "Sí" "No"))
(defmethod to-str :default [x] (String/valueOf x))


(defn clear-combo [combo]
  (.clear (cast ListModelList (.getModel combo))))

(defn load-combo  [entity records combo]
  (let [values (ArrayList.)]
    (.add values "Seleccionar...")
    ;; (.add values "--------------")
    (doseq [r records]
      (.add values ((:to-str entity) r)))
    (.addAll (cast ListModelList (.getModel combo)) values)))

(defn selected [model combo]
  (let [index (.getSelectedIndex combo)]
    (if (> index 0)
      (nth @model (- (.getSelectedIndex combo) 1))
      nil)))

(defn gen-cascade-filter [child child-combo child-model rows wrapper]
  (let [parent-combo (doto (Combobox.) (.setReadonly true))
	child-refs (:refs child)
	reference ((:filter-ref child) child-refs)
	relationship ((:rel reference)) 
	parent ((:to-entity relationship))
	parent-model (ref [])
	parent-row (Row.)
	on-select-parent #(do 
			    (clear-combo child-combo)
			    (load-combo child
					(let [result (search (:table-name child)
							     (to-fks-ref 
							      (:fks-pks relationship) 
							      (selected parent-model parent-combo)))]
					  (dosync (ref-set child-model result))) 
					child-combo))]
			       
    (add-event! child-combo "onSelect"
		#(when (= (.getSelectedIndex child-combo) 0)
		   (.setSelectedIndex parent-combo 0)))
			       
    (.setModel parent-combo (ListModelList.)) 
			       
    ;;load parent
    (load-combo parent 
		(let [result (search (:table-name parent))]
		  (dosync (ref-set parent-model result)))
		parent-combo)
			       
    ;;add to grid
			       
    (cascade-append!
     [(Label. (:ref-name reference)) parent-row rows]
     [parent-combo parent-row])
			       
			       
    ;;add select event to parent
    (add-event! 
     parent-combo "onSelect"
     #(do
	(on-select-parent)
	(add-event! child-combo "onAfterRender" 
		    (fn[](.setSelectedIndex child-combo 0)))))
			       
    ;;add to relationships
    (dosync 
     (alter wrapper merge 
	    {:setter 
	     #(let [parent-pks (to-pks-ref (:fks-pks relationship) %)
		    hack (ref nil)]
		(add-event! parent-combo "onAfterRender"
			    (fn[] (do (.setSelectedIndex parent-combo
							 (inc (first (positions (fn[x](is? parent x parent-pks))
										@parent-model))))
				      (add-event! child-combo "onAfterRender"
						  (fn[] (when (nil? @hack)
							  (.setSelectedIndex child-combo 
									     (inc (first (positions (fn[x](is? child x %)) @child-model))))
							  (dosync(ref-set hack "HACK ATTACK!")))))
				      (on-select-parent)
				      ))))                             
	     :enabler #(doseq [c [parent child]]
			 (.setButtonVisible c %))}))
    ))




;; TODO: replace this mess by a set of multimethods
;;; tab is used by reference selection widgets
;;; search-rows is mutated inside, in every case. Bad stuff
;;; scope is too used in the ref-box case
;;; widgets too is mutated inside. Candidate for return value.
;;;; thus, entity-type would be the only proper argument.
(defn gen-widget
  [entity-type widgets att-ref search-rows]
  
  
  (let [att (att-ref (:atts entity-type))
	  row (doto (Row.) (.setParent search-rows))
	  box (Hbox.)]
		 
      (case (:widget-type att)
		       ;; Not supported yet
		       ;;  :datetime ()
		       
		       :multi-option
		       (let [widget-type ((:widget-type (:aggregates att)) widget-types)
			     widget (to-class widget-type)]
			 (cascade-append!
			  [(Label. (str (:att-name att) ":")) row]
			  [widget box row])
			 (case (:widget-type (:aggregates att))
			       :combobox
			       (do 
				 (doseq [x (:options (:aggregates att))] (.appendItem widget x))
				 (.setReadonly widget true)
				 (dosync (alter widgets assoc att-ref 
						(Widget-wrapper.
						 #(hash-map att-ref (.getValue widget))
						 #(.setValue widget %)
						 #(.setButtonVisible widget %)))))
			       
			       :radiogroup
			       (do
				 (.setOrient widget (:orient (:aggregates att)))
				 (doseq [x (:options (:aggregates att))] 
				   (cascade-append! [(Radio. x) widget]))
					;(.setSelectedIndex widget 0)
				 (dosync (alter widgets assoc att-ref 
						(Widget-wrapper.
						 #(if (nil? (.getSelectedItem widget))
						    (hash-map att-ref nil)
						    (hash-map att-ref (.. widget getSelectedItem getLabel)))
						 #(.setSelectedItem widget (first(filter(fn[x](= (.getLabel x) %)) (.getItems widget))))
						 #(doseq [x (.getItems widget)] 
						    (.setDisabled x (not %)))))))))
		       
		       ;;rest
		       (let [widget-type ((:widget-type att) widget-types)
			     widget (to-class widget-type)]
			 (cascade-append!
			  [(Label. (str (:att-name att) ":")) row]
			  [widget box row])
			 (when (=  (:widget-type att))
			   :textarea
			   (do
			     (.setRows widget 5)
			     (.setWidth widget "500px")))
			 
			 (if (= :checkbox (:widget-type att))
			   (dosync (alter widgets assoc att-ref 
					  (Widget-wrapper.
					   #(hash-map att-ref (.isChecked widget))
					   #(.setChecked widget %)
					   #(.setReadonly widget (not %)))))
			   (dosync (alter widgets assoc att-ref 
					  (Widget-wrapper.
					   #(hash-map att-ref (.getValue widget))
					   #(.setValue widget %)
					   #(.setReadonly widget (not %)))))))))
  )

(defn gen-ref-widgets
  [entity-type scope tab widgets att-ref search-rows]
  (let [refe (att-ref (:refs entity-type))
	dir (:direction refe)
	rel (trampoline (:rel refe))
	card (:card rel)]
    
    (if (or (and (= :from dir) (= :many-to-one card))
	    (= :one-to-one card))
      
      ;;************** One reference **************
      ;; TODO: PRIORITY ONE FOR REFACTORING. 200 line+ expression
      (case (:in-form-type refe)
	    
	    :combo-box
	    
	    (let [child-combo (doto (Combobox.) (.setReadonly true))
		  child (trampoline (:to-entity rel))
		  child-model (ref [])
		  child-row (Row.)
		  wrapper (ref (Widget-wrapper. #(to-fks-ref (:fks-pks rel) (selected child-model child-combo)) nil nil))]
	      
	      (.setModel child-combo (ListModelList.))
	      
	      
	      
	      (if (nil? (:filter-ref child))
		
		(do
		  (load-combo 
		   child
		   (let [res (search (:table-name child))]
		     (dosync (ref-set child-model res))) 
		   child-combo)
		  (dosync (alter wrapper merge 
				 {:setter #(add-event! child-combo "onAfterRender"
						       (fn [] (do (.setSelectedIndex child-combo 
										     (inc (first (positions
												  (fn[x](is? child x %))
												  @child-model)))))))
				  
				  :enabler #(doto child-combo
					      (.setReadonly (not %))
					      (.setButtonVisible %))})))
		
		;;************** Cascade combos **************
		;;(!!!one level only)
		
		(let [parent-combo (doto (Combobox.) (.setReadonly true))
		      child-refs (:refs child)
		      reference ((:filter-ref child) child-refs)
		      relationship ((:rel reference)) 
		      parent ((:to-entity relationship))
		      parent-model (ref [])
		      parent-row (Row.)
		      on-select-parent #(do 
					  (clear-combo child-combo)
					  (load-combo child
						      (let [result (search (:table-name child)
									   (to-fks-ref 
									    (:fks-pks relationship) 
									    (selected parent-model parent-combo)))]
							(dosync (ref-set child-model result))) 
						      child-combo))]
		  
		  (.setModel parent-combo (ListModelList.)) 
		  
		  (load-combo parent 
			      (let [result (search (:table-name parent))]
				(dosync (ref-set parent-model result))) parent-combo)
		  
		  (cascade-append!
		   [(Label. (:ref-name reference)) parent-row search-rows]
		   [parent-combo parent-row])

		  (add-event! 
		   parent-combo "onSelect"
		   #(do
		      (on-select-parent)
		      (add-event! child-combo "onAfterRender" 
				  (fn []
				    (if (> (.getSelectedIndex parent-combo) 0)
				      (.setSelectedIndex child-combo 1)
				      (.setSelectedIndex child-combo 0))))))

		  
		  (dosync 
		   (alter wrapper merge 
			  {:setter 
			   #(let [parent-pks (to-pks-ref (:fks-pks relationship) %)
				  hack (ref nil)
				  hack-prima (ref nil)]
			      (add-event! parent-combo "onAfterRender"
					  (fn [] (do (.setSelectedIndex
						      parent-combo
						      (first (positions (fn[x](is? parent x parent-pks)) @parent-model)))
						     (when (nil? hack-prima)
						       (add-event! child-combo "onAfterRender"
								   (fn[] (when (nil? @hack)
									   (.setSelectedIndex
									    child-combo 
									    (inc (first (positions
											 (fn[x](is? child x %))
											 @child-model))))
									   (dosync(ref-set hack "HACK ATTACK!"))))))
						     (dosync (ref-set hack-prima "PHP es una buena herramienta"))
						     (on-select-parent)))))
			   :enabler #(doseq [c [parent child]]
				       (.setButtonVisible c %))}))
		  
		  (add-event! parent-combo "onAfterRender"
			      #(do
				 (.setSelectedIndex parent-combo 0) 
				 (on-select-parent)
				 (add-event! child-combo "onAfterRender"
					     (fn [] (.setSelectedIndex child-combo 0)))))))
	      
	      
	      (dosync (alter widgets assoc att-ref @wrapper))
	      
	      (cascade-append!
	       [(Label. (:ref-name refe)) child-row search-rows]
	       [child-combo child-row])
	      )
	    
	    
	    :ref-box
	    
	    (let [ref-box (doto (Textbox.) (.setReadonly true))
		  select-button (doto (Button. "Seleccionar") (.setImage (:up icons)))
		  edit-button (doto (Button. "Editar") (.setImage (:edit icons)))
		  to-entity ((:to-entity rel))
		  reference (ref {})
		  setter #(dosync 
			   (ref-set reference %)
			   (.setValue ref-box ((:to-str to-entity) %))
			   (.setDisabled edit-button false))
		  box (Hbox.)
		  row (Row.)]
	      
	      (cascade-append! 
	       [(Label. (str (:ref-name refe) ":")) row search-rows]
	       [ref-box box row])
	      
	      (cascade-append!
	       [select-button box])
	      
	      (add-event! select-button "onClick"
			  #(gen-ref-selector to-entity setter (:ref-name refe) (.getPage tab) scope))
	      
	      (add-event! edit-button "onClick"
			  #(gen-form to-entity reference scope))
	      
	      (.setDisabled edit-button true)
	      
	      (dosync (alter widgets assoc att-ref (Widget-wrapper. 
						    #(to-fks-ref (:fks-pks rel) @reference)
						    setter
						    #(.setDisabled select-button %))))))
      
      ;;************** Many references **************
      
      (list)))
  )

(defn gen-selector

  ;;************** When search **************
  ([entity-type scope]
     (gen-selector entity-type nil nil nil scope))
  
  ;;************** When in ref **************
  ([entity-type selection scope]
     (gen-selector entity-type selection nil nil scope))

  ;;************** When list selector **************
  ([entity-type reference record scope] 
     (gen-selector entity-type nil reference record scope))
  
  ([entity-type selection reference ref-record scope]
     
     ;;************** Globals **************
     
     (let [in-ref? (not (nil? selection))
	   list-selector? (not (nil? ref-record))
	   
	   ;;************** Constants **************
	   
	   key-search-atts (map (fn[x](first x))
				(filter 
				 (fn[x](= String (:data-type (second x)))) 
				 (:atts entity-type)))
	   
	   example-with-ref (if list-selector? (to-fks-ref (:fks-pks ((:rel(val reference)))) ref-record) nil)

	   ;;************** Mutables **************
	   
	   records (ref [])
	   headers (ref [])
	   search-widgets (ref {})
	   search-criteria (ref {:table-name (:table-name entity-type) :page 0 :per-page 30 :sort-field nil :sort-order nil})
	   search-fun (ref search)
	   search-aggregates (ref [])

	   ;;************** Layout **************
	   
	   layout (doto (Borderlayout.) (.setHeight "100%"))       
	   north-box (doto (Vbox.) (.setWidth "100%"))
	   center (doto (Center.) (.setFlex true))
	   tab (doto (Tab. (str "Búsqueda " (:multi-name entity-type))) (.setClosable true))
	   tab-panel (doto (Tabpanel.) (.setHeight "100%"))
	   
	   ;;************** Menubar **************
	   
	   ops-menu (Menubar.)
	   create-button (doto (Menuitem. "Agregar") (.setImage (:add icons)))
	   update-button (doto (Menuitem. "Editar") (.setDisabled true) (.setImage (:edit icons))) 
	   delete-button (doto (Menuitem. "Eliminar") (.setDisabled true) (.setImage (:remove icons)))
	   
	   
	   ;;************** Toolbar **************
	   
	   tool-bar (Toolbar.)
	   search-tbb (doto (Toolbarbutton. "Búsqueda avanzada")(.setImage (:down icons)))
	   search-button (doto (Menuitem. "Buscar")(.setImage (:find icons)))
	   
	   ;;************** Advanced search panel **************
	   
	   search-rows (Rows.)
	   search-panel (doto (Groupbox.) (.setMold "3d"))
	   search-box (Bandbox.)
	   
	   ;;************** Table **************
	   
	   table-model (ListModelList.)
	   table (doto (Listbox.) (.setModel table-model) (.setRows 30) (.setWidth "100%"))
	   table-head (Listhead.)
	   table-layout (doto (Borderlayout.) (.setVflex "true"))
	   center-box (Vbox.)
	   
	   paging (doto (Paging.) (.setPageSize (:per-page @search-criteria)))]

       ;;************** Functions **************
       
       (letfn [(load-page
		[]
		(let [result (apply @search-fun 
				    (apply conj @search-aggregates 
					   (apply vector 
						  (vals (dosync (alter search-criteria 
								       assoc :page (.getActivePage paging)))))))
		      rows (second result)]
		  (dosync (ref-set records rows))
		  (doto table-model 
		    (.clear)
		    (.addAll (let [x (ArrayList.)]
			       (doseq [row rows]
				 (.add x row)) x)))
		  (.setTotalSize paging (first result))
		  (when in-ref?
		    (selection false nil))
		  (.setDisabled update-button true)))
	       (key-search
		[]
		(dosync
		 (ref-set search-aggregates 
			  [(str "%" (.getValue search-box) "%") 
			   key-search-atts])))]

	 ;;************** Create GUI **************

	 (if-not in-ref? (cascade-append! 
			  [create-button ops-menu (North.) table-layout]
			  [update-button ops-menu]
			  [delete-button ops-menu]))
	 
	 (if (not (nil?(:icon entity-type)))
	   (.setImage tab ((:icon entity-type) icons)))

	 
	 (cascade-append!
	  
	  [tool-bar north-box (doto (North.) (.setFlex true)) layout tab-panel]
	  
	  [search-box tool-bar]

	  [search-button (Menubar.) search-panel]
	  
	  [(doto search-tbb (add-event! "onClick" 
				       #(.setOpen search-panel (not (.isOpen search-panel))))) tool-bar]
	  
	  [search-rows (doto (Grid.) (.appendChild (doto (Columns.) (.appendChild (doto (Column.) (.setWidth "20%"))))))
	   (doto search-panel (.setOpen false)) center-box]
	  
	  [table-head
	   table
	   (doto (Center.)(.setFlex true))
	   table-layout
	   center-box
	   center
	   layout]
	  
	  [paging (doto (South.) (.setFlex true)) layout])

	 ;;************** Create advanced search form **************

	 (let [widgets (ref {})]

	   (doseq [att-ref (:search-order entity-type)]
	     
	     ;; Horrible
	     (if (contains? (:atts entity-type) att-ref)
	       (gen-widget entity-type widgets  att-ref search-rows)
	       (gen-ref-widgets entity-type scope tab widgets att-ref search-rows)
	       )

	     ))
	 
	 ;;************** Create table **************	 
	 
	 (doseq [att-ref (:selector-order entity-type)]
	   (let [header (doto (Listheader. (if (contains? (:atts entity-type) att-ref) 
					     (:att-name (att-ref (:atts entity-type)))
					     (:ref-name (att-ref (:refs entity-type))))) (.setSort "auto"))]
	     (dosync (alter headers conj header))
	     (.appendChild table-head header)
	     (.addEventListener header "onSort" 
				(proxy [EventListener] []
				  (onEvent [e]
					   (doseq [h @headers]
					     (when (not (= h header))
					       (.setSortDirection h "natural")))
					   (let [opp {"ascending" "descending" "natural" "ascending" "descending" "ascending"}
						 sort-dir {"ascending" "#asc" "descending" "#desc"}
						 dir (.getSortDirection header)]
					     (.setSortDirection header (opp dir))
					     (dosync (alter search-criteria merge {:sort-order (sort-dir (opp dir))
										   :sort-field att-ref})))
					   (.stopPropagation e)
					   (load-page))))))
	 
	 (.setItemRenderer table 
			   (proxy [ListitemRenderer] []
			     (render [item record]
				     (doseq [att-ref (:selector-order entity-type)]
				       (.setParent (Listcell. (if (nil?(att-ref record))
								""
								(to-str (att-ref record)))) item)))))

	 ;;************** Events **************	 
	 
	 (add-event! paging "onPaging"
		    load-page)
	 
	 
	 (add-event! search-box "onOK"
		    (if list-selector?
		      #(do (key-search)
			   (dosync (ref-set search-fun search-by-key-example)
				   (alter search-aggregates conj example-with-ref))
			   (.setActivePage paging 0) 
			   (load-page))                    
		      #(do (key-search)
			   (dosync (ref-set search-fun search-by-key))
			   (.setActivePage paging 0)
			   (load-page))))
	 
	 (.addEventListener search-box "onChanging"
			    (if list-selector?
			      (proxy [EventListener][]
				(onEvent [e]
					 (dosync (ref-set search-aggregates 
							  [(str "%" (.getValue e) "%") 
							   key-search-atts
							   example-with-ref])
						 (ref-set search-fun search-by-key-example))
					 (.setActivePage paging 0)
					 (load-page)))
			      (proxy [EventListener][]
				(onEvent [e]
					 (dosync (ref-set search-aggregates 
							  [(str "%" (.getValue e) "%") 
							   key-search-atts])
						 (ref-set search-fun search-by-key))
					 (.setActivePage paging 0)
					 (load-page)))))
	 
	 (add-event! create-button "onClick"
		    (if list-selector? 
		      #(gen-form entity-type reference ref-record scope)
		      #(gen-form entity-type scope)))
	 
	 (add-event! update-button "onClick"
		    #(gen-form entity-type (ref (nth @records (.getSelectedIndex table))) scope))
	 
	 (add-event! table "onSelect"
		    #(do
		       (.setDisabled update-button false)
		       
		       (when in-ref?
			 (selection true (nth @records (.getSelectedIndex table))))))

	 ;;************** At start **************	 
	 
	 (when list-selector?
	   (dosync
	    (ref-set search-fun search-by-example)
	    (ref-set search-aggregates [(to-fks-ref (:fks-pks ((:rel(val reference)))) ref-record)]))
	   (when (not(:not-null? ((:rel (val reference))))) 
	     (cascade-append! [(doto (Menuitem. "Seleccionar preexistente(s)")
				(add-event! "onClick"
					   #(gen-ref-selector 
					     entity-type  
					     (fn[x](do (update entity-type x 
							       (merge x (to-fks-ref
									 (:fks-pks ((:rel (val reference))))
									 ref-record)))
						       (load-page))) 
					     (str (:ref-name (val reference)) 
						  " para " 
						  (:single-name ((:to-entity (val reference)))))
					     (.getPage tab)
					     scope))) 
			      ops-menu])))
	 (load-page)

	 ;;************** Returns **************	 
	 
	 (if (or in-ref? list-selector?)
	   [layout (fn[tn n o]
		     (when (= tn (:table-name entity-type))
		       (load-page)))]
	   [tab tab-panel (fn[tn n o]
			    (when (= tn (:table-name entity-type))
			      (load-page)))])))))

;;************** GEN FORM NAO! **************

(defn gen-form
  
  ;;************** On create **************
  ([entity-type scope]
     (gen-form entity-type (ref {}) nil nil scope))
  
  ;;************** On edit **************
  ([entity-type record scope]
     (gen-form entity-type record nil nil scope))
  
  ;;************** On create from reference **************
  ([entity-type reference record scope]
     (gen-form entity-type (ref {}) reference record scope))
  
  ([entity-type record from-ref from-record scope]
     
     ;;************** Globals **************
     
     (let [referring? (not (nil? from-ref))
	   
	   ;;************** Constants **************
	   
	   ;;************** Mutables **************
	   widgets (ref {})
	   editing? (ref (not (empty? @record)))
	   list-refs-signal (ref nil)
	   signals (ref nil)
	   
	   ;;************** Form **************
	   grid (doto (Grid.) 
		  (.appendChild 
		   (doto (Columns.) 
		     (.appendChild 
		      (doto (Column.) (.setWidth "20%")))
		     (.appendChild 
		      (Column.)))))
	   rows (Rows.)
	   layout (Borderlayout.)
	   menu-bar (Menubar.)
	   save-button (doto (Menuitem. (if @editing? 
					  "Guardar cambios" 
					  (str "Crear " (:single-name entity-type)))) (.setImage (:save icons)))
	   tab (doto (Tab. (if @editing? 
			     (str "Editando " (:single-name entity-type) " | " ((:to-str entity-type) @record)) 
			     (str "Creando " (:single-name entity-type)))) (.setClosable true))
	   
	   tab-panel (doto (Tabpanel.) (.setHeight "100%"))]
       
       ;;************** Functions **************
       
       (letfn [(disable-pks
		[]
		(doseq [pk (pks-att-ref entity-type)]
		  (if (not(nil? (pk @widgets)))
		    ((:enabler (pk @widgets)) false))))
	       
	       (set-values
		[]
		(doseq [[att-ref widget] @widgets]
		  ;; Problemas de colisión de nombre. Soluciones posibles:
		  ;;; tags de metadata - vil hack
		  ;;; parapeto para discriminar por tipos de los valores
		  ;;; conjuntos separados como reemplazo de 'widgets'
		  (if (contains? (:atts entity-type) att-ref) 
		    ((:setter widget) (att-ref @record))
		    (let [rel ((:rel (att-ref (:refs entity-type))))]
		      ;; Suponer solo referencias many-to-one
		      ((:setter widget) (first (search (:table-name ((:to-entity rel))) 
						       (to-pks-ref (:fks-pks rel) @record))))))))
	       (get-values
		[]
		(apply merge
		       (for [[att-ref w] @widgets]
			 ((:getter w)))))
	       
	       (tab-label
		[]
		(if @editing? 
		  (str "Editando " (:single-name entity-type) " | " ((:to-str entity-type) @record)) 
		  (str "Creando " (:single-name entity-type))))]
	 
	 
	 (if (not (nil?(:icon entity-type)))
	   (.setImage tab ((:icon entity-type) icons)))
	 
	 (.addEventListener tab "onClose"
			    (proxy [EventListener][]
			      (onEvent[e]
				      (when @editing?
					(let [values (get-values)] 
					  (if (not= (select-keys @record (keys values)) values)
					    (do
					      (.stopPropagation e)
					      (Messagebox/show  
					       "Tiene cambios sin guardar, desea cerrar este formulario?"
					       "Cerrar formulario"
					       (bit-xor Messagebox/YES Messagebox/NO)                                  
					       Messagebox/QUESTION
					       (proxy [EventListener] []
						 (onEvent[e]
							 (if (= (.. e getData intValue) Messagebox/YES)
							   (do
							     (.setSelected tab false)
							     ((scope :close-signal) tab)
							     (.detach tab)
							     (.detach tab-panel))
							   (.setSelected tab true))))))
					    ((scope :close-signal) tab)))))))
	 
	 (cascade-append!       
	  [rows grid (doto (Center.) (.setFlex true)) layout tab-panel]
	  [save-button menu-bar (North.) layout])
	 
	 ;;ADD ATTS AND REFS
	 
	 (doseq [att-ref (:form-order entity-type)] 
	   ;;Horrible, evasión de colisiones de nombre
	   
	   (if (contains? (:atts entity-type) att-ref)
	     
	     ;;*** Attributes ***
	     
	     (let [att (att-ref (:atts entity-type))
		   row (doto (Row.) (.setParent rows))
		   box (Hbox.)]
	       
	       
	       (case (:widget-type att)
		     
		     ;; rare
		     
		     :image ()
		     ;; bosquejo
		     :file (let [file-box (doto (Textbox.) (.setReadonly true))
				 up-button (Button. "Examinar...")
				 down-button (Button. "Descargar")
				 remove-button (Button. "Eliminar")
				 file (ref nil)]
			     (when (:not-null? att)
			       (.setConstraint file-box "no empty"))
			     (cascade-append!
			      [(Label. (str (:att-name att) ":")) row]
			      [file-box box row]
			      [up-button box]
			      [down-button box]
			      [remove-button box])
			     (dosync (alter widgets assoc att-ref 
					    (Widget-wrapper.
					     nil
					     nil
					     #(.setDisabled up-button (not %))))))
		     :datetime ()
		     :multi-option (let [widget-type ((:widget-type (:aggregates att)) widget-types)
					 widget (to-class widget-type)]
				     (cascade-append!
				      [(Label. (str (:att-name att) ":")) row]
				      [widget box row])
				     (case (:widget-type (:aggregates att))
					   
					   :combobox
					   (do 
					     (doseq [x (:options (:aggregates att))] (.appendItem widget x))
					     (.setReadonly widget true)
					     (when (:not-null? att)
					       (.setConstraint widget "no empty"))
					     (dosync (alter widgets assoc att-ref 
							    (Widget-wrapper.
							     #(hash-map att-ref (.getValue widget))
							     #(.setValue widget %)
							     #(.setButtonVisible widget %)))))
					   
					   :radiogroup
					   (do
					     (.setOrient widget (:orient (:aggregates att)))
					     (doseq [x (:options (:aggregates att))] 
					       (cascade-append! [(Radio. x) widget]))
					     (dosync (alter widgets assoc att-ref 
							    (Widget-wrapper.
							     #(if (nil? (.getSelectedItem widget))
								(if (:not-null? att)
								  (throw (WrongValueException.
									  widget
									  "No se permite vacio o espacios en blanco. Debe especificar un valor diferente"))
								  (hash-map att-ref nil))
								(hash-map att-ref (.. widget getSelectedItem getLabel)))
							     #(.setSelectedItem widget (first (filter
											       (fn[x](= (.getLabel x) %))
											       (.getItems widget))))
							     #(doseq [x (.getItems widget)] 
								(.setDisabled x (not %)))))))))
		     
		     ;;rest
		     (let [widget-type ((:widget-type att) widget-types)
			   widget (to-class widget-type)]
		       (when (:not-null? att)
			 (.setConstraint widget "no empty"))
		       (cascade-append!
			[(Label. (str (:att-name att) ":")) row]
			[widget box row])
		       (case (:widget-type att)
			     
			     :textarea 
			     (do           
			       (.setRows widget 5)
			       (.setWidth widget "500px"))
			     
			     :textbox
			     ;;; aggregates es para funcionalidad opcional
			     ;;; o configuración (e.g. longitud)
			     (when-not (nil? (:aggregates att))
			       (let [aggs (:aggregates att)]
				 (when-not (nil? (:actions aggs))
				   (doseq [x (:actions aggs)]
				     (let [butt (doto (Button. (:label x))
						  (.setImage ((:icon x) icons))
						  (.setParent box))] 
				       (add-event! butt "onClick"
						  (fn []((:function x) butt (.getValue widget)))))))))
			     nil)
		       (when (some #(= (:widget-type att) %) [:intbox :doublebox :longbox])
			 (when-not (nil? (:aggregates att))
			   (let [aggs (:aggregates att)]
			     (when-not (nil? (:format aggs))
			       (.setFormat widget (:format aggs)))
			     (when-not (nil? (:actions aggs))
			       ;;cosas
			       ))))
		       (if (= :checkbox (:widget-type att))
			 (dosync (alter widgets assoc att-ref 
					(Widget-wrapper.
					 #(hash-map att-ref (.isChecked widget))
					 #(.setChecked widget %)
					 #(.setReadonly widget (not %)))))
			 (dosync (alter widgets assoc att-ref 
					(Widget-wrapper.
					 #(hash-map att-ref (.getValue widget))
					 #(.setValue widget %)
					 #(.setReadonly widget (not %)))))))))
	     
	     ;;*** Relationships ***
	     
	     (let [refe (att-ref (:refs entity-type))
		   dir (:direction refe) ;; :from o :to
		   rel ((:rel refe))
		   card (:card rel)]
	       
	       (if (or (and (= :from dir) (= :many-to-one card))
		       (= :one-to-one card))
		 
		 ;; one reference
		 
		 (case (:in-form-type refe)
		       
		       :combo-box
		       
		       (letfn
			   [(load-combo 
			     [entity records combo]
			     (let [values (ArrayList.)]
			       (.add values "Seleccionar...")
			       (doseq [r records]
				 (.add values ((:to-str entity) r)))
			       (.addAll (cast ListModelList (.getModel combo)) values)))
			    
			    (clear-combo
			     [combo]
			     (.clear (cast ListModelList (.getModel combo))))
			    
			    (selected
			     [model combo]
			     (let [index (.getSelectedIndex combo)]
			       (if (> index 0)
				 (nth @model (- (.getSelectedIndex combo) 1))
				 nil)))]
			 
			 (let [child-combo (doto (Combobox.) (.setReadonly true))
			       child ((:to-entity rel))
			       child-model (ref [])
			       child-row (Row.)
			       wrapper (ref (Widget-wrapper.
					     #(let [selection (selected child-model
									child-combo)]
						(if (and (:not-null? rel) (nil? selection))
						  (throw (WrongValueException.
							  child-combo
							  "No se permite vacio o espacios en blanco. Debe especificar un valor diferente"))
						  (to-fks-ref (:fks-pks rel)
							      selection)))
					     nil
					     nil))]
			   
			   (.setModel child-combo (ListModelList.))			   
			   
			   (if (nil? (:filter-ref child))
			     
			     ;; hijo sin padre. Forever alone
			     (let [load-child #(load-combo 
						child
						(let [res (search (:table-name child))]
						  (dosync (ref-set child-model res))) 
						child-combo)]
			       (load-child)
			       (dosync (alter wrapper merge 
					      {:setter #(add-event! child-combo "onAfterRender"
								   (fn[] (do (.setSelectedIndex child-combo 
												(first (positions (fn [x] (is? child x %)) @child-model))))))
					       
					       :enabler #(doto child-combo
							   (.setReadonly (not %))
							   (.setButtonVisible %))}))
			       (dosync
				(alter signals conj (fn [table-name old-record new-record]
						      (when (= table-name (:table-name child))
							(clear-combo child-combo)
							(load-child))))))

			     ;;*** with cascade filter ***)
			     ;;(!!!one level only)
			     (gen-cascade-filter child child-combo child-model rows wrapper)
			     )
			   
			   (dosync (alter widgets assoc att-ref @wrapper))
			   
			   (cascade-append!
			    [(Label. (:ref-name refe)) child-row rows]
			    [child-combo child-row])))
		       
					;*** with ref box ***
		       
		       :ref-box
		       
		       (let [ref-box (doto (Textbox.) (.setReadonly true))
			     select-button (doto (Button. "Seleccionar") (.setImage (:up icons)))
			     edit-button (doto (Button. "Editar") (.setImage (:edit icons)))
			     to-entity ((:to-entity rel))
			     reference (ref {})
			     setter #(dosync 
				      (ref-set reference %)
				      (.setValue ref-box ((:to-str to-entity) %))
				      (.setDisabled edit-button false))
			     box (Hbox.)
			     row (Row.)]
			 
			 (cascade-append! 
			  [(Label. (str (:ref-name refe) ":")) row rows]
			  [ref-box box row])
			 
			 (when (:not-null? rel)
			   (.setConstraint ref-box "no empty"))
			 
			 (when-not (or (:pk? rel) (and referring? (= (key from-ref) att-ref))) 
			   (cascade-append!
			    [edit-button box]
			    [select-button box]))
			 
			 (add-event! select-button "onClick"
				    #(gen-ref-selector to-entity setter (:ref-name refe) (.getPage tab) scope))
			 
			 (add-event! edit-button "onClick"
				    #(gen-form to-entity reference scope))
			 
			 (.setDisabled edit-button true)
			 
			 (dosync (alter widgets assoc att-ref (Widget-wrapper. 
							       #(to-fks-ref (:fks-pks rel) @reference)
							       setter
							       #(.setDisabled select-button %))))))
		 
		 ;; many references
		 (case (:in-form-type refe)
		       :selector
		       (let [from-entity ((:from-entity rel))
			     row (Row.)
			     signal #(let [selector (gen-selector from-entity 
								  (find (:refs from-entity) (:mutual-ref (:aggregates refe))) 
								  @record
								  scope)]
				       (cascade-append!
					[(doto 
					     (first selector) 
					   (.setWidth "500px") 
					   (.setHeight "500px")) row rows])
				       (dosync (alter signals conj (second selector))))]
			 (cascade-append! 
			  [(Label. (str (:ref-name refe) ":")) row ])
			 (if @editing?
			   (signal)
			   (dosync
			    (alter list-refs-signal conj signal))))
		       :selector-form
		       ()
		       :button
		       ()
					;many-to-many
		       :checkgroup
		       ()
		       :list
		       ())
		 ))))
	 
	 (add-event! save-button "onClick"            
		    #(let[rec (if (nil? @record) 
				(get-values)
				(merge (get-values) @record))
			  query (fn [fun params msg]
				  (let [result (apply fun params)
					was-editing? @editing?]
				    (if (not(nil? result))
				      (let [new-record (if (and (not @editing?) (:auto-inc-pk? entity-type))
							 (last result)
							 (first (filter (fn[x] (is? entity-type x rec)) result)))]
					
					((scope :signal) (:table-name entity-type) new-record rec)
					(dosync (ref-set record new-record)
						(ref-set editing? true)
						(.setLabel tab (tab-label)))
					(when-not was-editing?
					  (doseq [sig @list-refs-signal]
					    (sig))
					  (.setLabel save-button "Guardar cambios")
					;(disable-pks)
					  ))
				      (Messagebox/show  
				       "Error en la operación"
				       "Error"
				       Messagebox/OK
				       Messagebox/EXCLAMATION))))]
		       
		       (if @editing?
					;Editing
			 (query update [(:table-name entity-type) @record (get-values)] "")
			 
					;Creating
			 (if (and (not(:auto-inc-pk? entity-type))) 
			   (if-not (exists? (:table-name entity-type) (select-keys rec (pks entity-type)))
			     (query create [(:table-name entity-type) (get-values)] "")
			     (Messagebox/show  
			      "Objeto duplicado en la base de datos"
			      "Error"
			      Messagebox/OK
			      Messagebox/EXCLAMATION))
			   (query create [(:table-name entity-type) (get-values)] "")))))
	 
	 ((scope :gen-view) tab tab-panel (fn 
					    [table-name new-rec old-rec]
					    (doseq [s @signals]
					      (s table-name new-rec old-rec))
					    (when (= old-rec @record)
					      (dosync (ref-set record new-rec))
					      (set-values))) 
	  record #(and @editing? (= @record %)))
	 
	 (when @editing?
	   (set-values)
					;(disable-pks)
	   )
	 
	 (when referring?
	   (let[ref-key (key from-ref)
		ref-val (val from-ref)]
	     ((:setter (ref-key @widgets)) from-record)
	     ((:enabler (ref-key @widgets)) false)))))))

(defn gen-ref-selector
  [entity-type setter ref-name page scope]
  (let [win (doto (Window. nil "normal" true) (.setWidth "600px") (.setHeight "400px"))
	layout (Borderlayout.)
	select-button (doto (Menuitem. "Finalizar selección") (.setDisabled true) (.setImage (:accept icons))) 
	center (doto (Center.) (.setFlex true))
	reference (ref {})]
    
    (if (not (nil?(:icon entity-type)))
      (.appendChild win (Caption. (str "Selección " ref-name) ((:icon entity-type) icons))))
    
    (cascade-append! 
     [center layout win]
     [select-button (Menubar.) (North.) layout]
     [(doto (first (gen-selector entity-type #(do
						(.setDisabled select-button (not %1))
						(dosync(ref-set reference %2))) scope))) center])
    
    (add-event! select-button "onClick"
	       #(do
		  (setter @reference)
		  (.detach win)))
    
    (doto win
      (.setPage page)
      (.setMode "modal"))))