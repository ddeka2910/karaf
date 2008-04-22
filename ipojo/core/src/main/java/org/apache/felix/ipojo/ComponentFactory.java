/* 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.felix.ipojo;

import java.net.URL;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.felix.ipojo.architecture.ComponentTypeDescription;
import org.apache.felix.ipojo.metadata.Attribute;
import org.apache.felix.ipojo.metadata.Element;
import org.apache.felix.ipojo.parser.PojoMetadata;
import org.apache.felix.ipojo.util.Logger;
import org.apache.felix.ipojo.util.Tracker;
import org.apache.felix.ipojo.util.TrackerCustomizer;
import org.osgi.framework.BundleContext;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;

/**
 * The component factory manages component instance objects. This management
 * consist in creating and managing component instance build with the component
 * factory. This class could export Factory and ManagedServiceFactory services.
 * 
 * @author <a href="mailto:dev@felix.apache.org">Felix Project Team</a>
 */
public class ComponentFactory extends IPojoFactory implements TrackerCustomizer {

    /**
     * Tracker used to track required handler factories.
     */
    protected Tracker m_tracker;

    /**
     * Class loader to delegate loading.
     */
    private FactoryClassloader m_classLoader = null;

    /**
     * Component Implementation class.
     */
    private byte[] m_clazz = null;

    /**
     * Component Implementation Class Name.
     */
    private String m_classname = null;

    /**
     * Manipulation Metadata of the internal POJO.
     */
    private PojoMetadata m_manipulation = null;

    /**
     * Create a instance manager factory. The class is given in parameter. The
     * component type is not a composite.
     * @param context : bundle context
     * @param clazz : the component class
     * @param element : metadata of the component
     * @throws ConfigurationException occurs when the element describing the factory is malformed.
     */
    public ComponentFactory(BundleContext context, byte[] clazz, Element element) throws ConfigurationException {
        this(context, element);
        m_clazz = clazz;
    }

    /**
     * Create a instance manager factory.
     * @param context : bundle context
     * @param element : metadata of the component to create
     * @throws ConfigurationException occurs when the element describing the factory is malformed.
     */
    public ComponentFactory(BundleContext context, Element element) throws ConfigurationException {
        super(context, element);
        check(element); // NOPMD. This invocation is normal.
    }

    public ComponentTypeDescription getComponentTypeDescription() {
        return new PrimitiveTypeDescription(this);
    }

    /**
     * Check method : allow a factory to check if given element are correct.
     * A component factory metadata are correct if they contain the 'classname' attribute.
     * @param element : the metadata
     * @throws ConfigurationException occurs when the element describing the factory is malformed.
     */
    public void check(Element element) throws ConfigurationException {
        m_classname = element.getAttribute("classname");
        if (m_classname == null) { throw new ConfigurationException("A component needs a class name : " + element); }
    }

    public String getClassName() {
        return m_classname;
    }

    /**
     * Create a primitive instance.
     * @param config : instance configuration
     * @param context : service context.
     * @param handlers : handler to use
     * @return the created instance
     * @throws org.apache.felix.ipojo.ConfigurationException : if the configuration process failed.
     * @see org.apache.felix.ipojo.IPojoFactory#createInstance(java.util.Dictionary, org.apache.felix.ipojo.IPojoContext, org.apache.felix.ipojo.HandlerManager[])
     */
    public ComponentInstance createInstance(Dictionary config, IPojoContext context, HandlerManager[] handlers) throws org.apache.felix.ipojo.ConfigurationException {
        InstanceManager instance = new InstanceManager(this, context, handlers);
        instance.configure(m_componentMetadata, config);
        try {
            instance.start();
            return instance;
        } catch (IllegalStateException e) {
            // An exception occurs during the start method.
            m_logger.log(Logger.ERROR, e.getMessage(), e);
            throw new ConfigurationException(e.getMessage());
        }

    }

    /**
     * Define a class.
     * @param name : qualified name of the class
     * @param clazz : byte array of the class
     * @param domain : protection domain of the class
     * @return the defined class object
     */
    public Class defineClass(String name, byte[] clazz, ProtectionDomain domain) {
        if (m_classLoader == null) {
            m_classLoader = new FactoryClassloader();
        }
        return m_classLoader.defineClass(name, clazz, domain);
    }

    /**
     * Return the URL of a resource.
     * @param resName : resource name
     * @return the URL of the resource
     */
    public URL getResource(String resName) {
        return m_context.getBundle().getResource(resName);
    }

    /**
     * Load a class.
     * @param className : name of the class to load
     * @return the resulting Class object
     * @throws ClassNotFoundException 
     * @throws ClassNotFoundException : happen when the class is not found
     */
    public Class loadClass(String className) throws ClassNotFoundException {
        if (m_clazz != null && className.equals(m_classname)) {
            // Used the factory classloader to load the component implementation
            // class
            if (m_classLoader == null) {
                m_classLoader = new FactoryClassloader();
            }
            return m_classLoader.defineClass(m_classname, m_clazz, null);
        }
        return m_context.getBundle().loadClass(className);
    }

    /**
     * Start the factory.
     */
    public synchronized void starting() {
        if (m_requiredHandlers.size() != 0) {
            try {
                String filter = "(&(" + Handler.HANDLER_TYPE_PROPERTY + "=" + PrimitiveHandler.HANDLER_TYPE + ")" + "(factory.state=1)" + ")";
                m_tracker = new Tracker(m_context, m_context.createFilter(filter), this);
                m_tracker.open();
            } catch (InvalidSyntaxException e) {
                m_logger.log(Logger.ERROR, "A factory filter is not valid: " + e.getMessage());
                stop();
            }
        }
    }

    /**
     * Stop all the instance managers.
     */
    public synchronized void stopping() {
        if (m_tracker != null) {
            m_tracker.close();
            m_tracker = null;
        }
        m_classLoader = null;
        m_clazz = null;
    }

    /**
     * Compute the factory name.
     * @return the factory name.
     */
    public String getFactoryName() {
        String name = m_componentMetadata.getAttribute("name");
        if (name == null) { // No factory name, try with factory attribute
            name = m_componentMetadata.getAttribute("factory");
            if (name == null || name.equalsIgnoreCase("true") || name.equalsIgnoreCase("false")) { // Avoid boolean case
                name = m_componentMetadata.getAttribute("classname");
            }
        }
        return name;
    }

    /**
     * Compute required handlers.
     * @return the required handler list.
     */
    public List getRequiredHandlerList() {
        List list = new ArrayList();
        Element[] elems = m_componentMetadata.getElements();
        for (int i = 0; i < elems.length; i++) {
            Element current = elems[i];
            if (!"manipulation".equals(current.getName())) {
                RequiredHandler req = new RequiredHandler(current.getName(), current.getNameSpace());
                if (!list.contains(req)) {
                    list.add(req);
                }
            }
        }

        // Add architecture if architecture != 'false'
        String arch = m_componentMetadata.getAttribute("architecture");
        if (arch == null || arch.equalsIgnoreCase("true")) {
            list.add(new RequiredHandler("architecture", null));
        }

        // Add lifecycle callback if immediate = true
        RequiredHandler reqCallback = new RequiredHandler("callback", null);
        String imm = m_componentMetadata.getAttribute("immediate");
        if (!list.contains(reqCallback) && imm != null && imm.equalsIgnoreCase("true")) {
            list.add(reqCallback);
        }

        return list;
    }

    /**
     * A new handler factory is detected.
     * Test if the factory can be used or not.
     * @param reference : the new service reference.
     * @return true if the given factory reference match with a required handler.
     * @see org.apache.felix.ipojo.util.TrackerCustomizer#addingService(org.osgi.framework.ServiceReference)
     */
    public boolean addingService(ServiceReference reference) {
        for (int i = 0; i < m_requiredHandlers.size(); i++) {
            RequiredHandler req = (RequiredHandler) m_requiredHandlers.get(i);
            if (req.getReference() == null && match(req, reference)) {
                int oldP = req.getLevel();
                req.setReference(reference);
                // If the priority has changed, sort the list.
                if (oldP != req.getLevel()) {
                    Collections.sort(m_requiredHandlers);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * A matching service has been added to the tracker, we can no compute the factory state.
     * @param reference : added reference.
     * @see org.apache.felix.ipojo.util.TrackerCustomizer#addedService(org.osgi.framework.ServiceReference)
     */
    public void addedService(ServiceReference reference) {
        if (m_state == INVALID) {
            computeFactoryState();
        }
    }

    /**
     * A used factory disappears.
     * @param reference : service reference.
     * @param service : factory object.
     * @see org.apache.felix.ipojo.util.TrackerCustomizer#removedService(org.osgi.framework.ServiceReference, java.lang.Object)
     */
    public void removedService(ServiceReference reference, Object service) {
        // Look for the implied reference and invalid the handler identifier
        for (int i = 0; i < m_requiredHandlers.size(); i++) {
            RequiredHandler req = (RequiredHandler) m_requiredHandlers.get(i);
            if (reference.equals(req.getReference())) {
                req.unRef(); // This method will unget the service.
                computeFactoryState();
                return; // The factory can be used only once.
            }
        }
    }

    /**
     * A used handler factory is modified.
     * @param reference : the service reference
     * @param service : the Factory object (if already get)
     * @see org.apache.felix.ipojo.util.TrackerCustomizer#modifiedService(org.osgi.framework.ServiceReference, java.lang.Object)
     */
    public void modifiedService(ServiceReference reference, Object service) {
        // Noting to do
    }

    /**
     * Returns manipulation metadata of this component type.
     * The returned object is computed at the first call and then is cached.
     * @return manipulation metadata of this component type.
     */
    public PojoMetadata getPojoMetadata() {
        if (m_manipulation == null) {
            m_manipulation = new PojoMetadata(m_componentMetadata);
        }
        return m_manipulation;
    }

    /**
     * FactoryClassloader.
     */
    private class FactoryClassloader extends ClassLoader {

        /**
         * Map of defined classes [Name, Class Object].
         */
        private final Map m_definedClasses = new HashMap();

        /**
         * The defineClass method.
         * @param name : name of the class
         * @param clazz : the byte array of the class
         * @param domain : the protection domain
         * @return : the defined class.
         */
        public Class defineClass(String name, byte[] clazz, ProtectionDomain domain) {
            if (m_definedClasses.containsKey(name)) { return (Class) m_definedClasses.get(name); }
            Class clas = super.defineClass(name, clazz, 0, clazz.length, domain);
            m_definedClasses.put(name, clas);
            return clas;
        }

        /**
         * Return the URL of the asked resource.
         * @param arg : the name of the resource to find.
         * @return the URL of the resource.
         * @see java.lang.ClassLoader#getResource(java.lang.String)
         */
        public URL getResource(String arg) {
            return m_context.getBundle().getResource(arg);
        }

        /**
         * Load the class.
         * @see java.lang.ClassLoader#loadClass(java.lang.String, boolean)
         * @param name : the name of the class
         * @param resolve : should be the class resolve now ?
         * @return : the loaded class
         * @throws ClassNotFoundException : the class to load is not found
         */
        protected synchronized Class loadClass(String name, boolean resolve) throws ClassNotFoundException {
            return m_context.getBundle().loadClass(name);
        }
    }

    private final class PrimitiveTypeDescription extends ComponentTypeDescription {

        /**
         * Constructor.
         * @param factory : the represented factory.
         */
        public PrimitiveTypeDescription(Factory factory) {
            super(factory);
        }

        /**
         * Compute the properties to publish : 
         * component.class contains the pojo class name.
         * @return the dictionary of properties to publish
         * @see org.apache.felix.ipojo.architecture.ComponentTypeDescription#getPropertiesToPublish()
         */
        public Dictionary getPropertiesToPublish() {
            Dictionary dict = super.getPropertiesToPublish();
            if (m_classname != null) {
                dict.put("component.class", m_classname);
            }
            return dict;
        }

        /**
         * Add the "implementation-class" attribute to the type description.
         * @return the component type description.
         * @see org.apache.felix.ipojo.architecture.ComponentTypeDescription#getDescription()
         */
        public Element getDescription() {
            Element elem = super.getDescription();
            elem.addAttribute(new Attribute("Implementation-Class", m_classname));
            return elem;
        }
    }
}
