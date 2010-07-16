/**
 * Copyright 2010 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.camel.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.camel.Exchange;
import org.apache.camel.ExchangePattern;
import org.apache.camel.Message;
import org.apache.camel.Navigate;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.component.cxf.CxfConstants;
import org.apache.camel.model.BeanDefinition;
import org.apache.camel.model.DataFormatDefinition;
import org.apache.camel.model.MarshalDefinition;
import org.apache.camel.model.PolicyDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.model.SendDefinition;
import org.apache.camel.model.ToDefinition;
import org.apache.camel.model.UnmarshalDefinition;
import org.apache.camel.model.dataformat.JaxbDataFormat;
import org.apache.camel.model.dataformat.JsonDataFormat;
import org.apache.camel.model.dataformat.XStreamDataFormat;
import org.apache.camel.processor.DelegateProcessor;
import org.apache.camel.processor.SendProcessor;
import org.apache.camel.spi.DataFormat;
import org.apache.camel.spi.Policy;
import org.apache.camel.spi.RouteContext;
import org.drools.command.runtime.BatchExecutionCommand;
import org.drools.command.runtime.GetGlobalCommand;
import org.drools.command.runtime.SetGlobalCommand;
import org.drools.command.runtime.process.AbortWorkItemCommand;
import org.drools.command.runtime.process.CompleteWorkItemCommand;
import org.drools.command.runtime.process.SignalEventCommand;
import org.drools.command.runtime.process.StartProcessCommand;
import org.drools.command.runtime.rule.FireAllRulesCommand;
import org.drools.command.runtime.rule.GetObjectsCommand;
import org.drools.command.runtime.rule.InsertElementsCommand;
import org.drools.command.runtime.rule.InsertObjectCommand;
import org.drools.command.runtime.rule.ModifyCommand;
import org.drools.command.runtime.rule.QueryCommand;
import org.drools.command.runtime.rule.RetractCommand;
import org.drools.command.runtime.rule.ModifyCommand.SetterImpl;
import org.drools.common.DefaultFactHandle;
import org.drools.common.DisconnectedFactHandle;
import org.drools.core.util.StringUtils;
import org.drools.runtime.CommandExecutor;
import org.drools.runtime.impl.ExecutionResultImpl;
import org.drools.runtime.pipeline.impl.ExecutionNodePipelineContextImpl;
import org.drools.runtime.rule.impl.FlatQueryResults;
import org.drools.xml.jaxb.util.JaxbListWrapper;
import org.mvel2.optimizers.impl.refl.nodes.ArrayLength;

public class DroolsPolicy
    implements
    Policy {
    private DroolsEndpoint dep;
    
    public void beforeWrap(RouteContext routeContext,
                           ProcessorDefinition processorDefinition) {
        augmentNodes( routeContext,
                      processorDefinition,
                      new HashSet() );
    }    

    public Processor wrap(RouteContext routeContext,
                          Processor processor) {
        RouteDefinition routeDef = routeContext.getRoute();
        
        ToDefinition toDrools = getDroolsNode( routeDef );

        Processor returnedProcessor;
        if ( toDrools != null ) {
            returnedProcessor = new DroolsProcess( toDrools.getUri(),
                                                   processor );
        } else {
            returnedProcessor =  processor;//new DroolsClientProcessor( processor );
        }
        return returnedProcessor;
    }

    private ToDefinition getDroolsNode(RouteDefinition routeDef) {
        ToDefinition toDrools = null;
        for ( ProcessorDefinition child : routeDef.getOutputs() ) {
            toDrools = getDroolsNode( child );
            if ( toDrools != null ) {
                break;
            }
        }
        return toDrools;
    }    
    
    public static void augmentNodes(RouteContext routeContext, ProcessorDefinition nav, Set visited) {
        if ( !nav.getOutputs().isEmpty() ) {

            List<ProcessorDefinition> outputs = nav.getOutputs();
            for ( int i = 0; i <  outputs.size(); i++  ) {
            //for ( Iterator<ProcessorDefinition> it = nav.getOutputs().iterator(); it.hasNext(); ) {
                ProcessorDefinition child = outputs.get( i );//it.next();
                if ( child instanceof ToDefinition) {
                    ToDefinition to = ( ToDefinition ) child;
                    if (to.getUri().startsWith( "cxfrs" ) && !visited.contains( to ) ) {                        
//                        it.remove();
                        BeanDefinition beanDef = new BeanDefinition();
                        beanDef.setBeanType( PreCxfrs.class );
                        outputs.add( i, beanDef ); // insert before cxfrs
                        beanDef = new BeanDefinition();
                        beanDef.setBeanType( PostCxfrs.class );
                        outputs.add( i+2, beanDef ); // insert after cxfrs
                        i = i + 2;// adjust for the two inserts
                        
//                        System.out.println( to.getUri( ) );
//                        ToDefinition newTo = new ToDefinition( to.getUri() );
//                        beanDef.addOutput( newTo );
//                        newOutputs.add( beanDef );
//                        
//                        BeanDefinition beanDef2 = new BeanDefinition();
//                        beanDef2.setBeanType( DummyProcess.class );
//                        //beanDef.addOutput( beanDef2 );
//                        to.addOutput( beanDef2 );
//                        to.bean(  DummyProcess.class );
//                        
//                        visited.add( to );
                    }
                    
                } else if ( child instanceof MarshalDefinition ) {
                    MarshalDefinition m = (MarshalDefinition) child;

                    DataFormatDefinition dformatDefinition = m.getDataFormatType();    
                    
                    if ( dformatDefinition == null ) {
                        String ref = m.getRef();
                        if ( "json".equals( ref ) ) {
                            dformatDefinition = new XStreamDataFormat();
                            ((XStreamDataFormat)dformatDefinition).setDriver( "json" );                            
                        } else if ( "xstream".equals( ref ) ) {
                            dformatDefinition = new XStreamDataFormat();
                        } else if ( "jaxb".equals( ref ) ) {
                            dformatDefinition = new JaxbDataFormat();
                        } else {
                            dformatDefinition = routeContext.getCamelContext().resolveDataFormatDefinition(ref);
                        }
                    }
                    
                    // always clone before changing
                    dformatDefinition = new FastCloner().deepClone( dformatDefinition );                    
                    
                    if ( dformatDefinition instanceof JaxbDataFormat ) {
                        dformatDefinition = augmentJaxbDataFormatDefinition( (JaxbDataFormat) dformatDefinition );                    
                    } else if ( dformatDefinition instanceof XStreamDataFormat ) { 
                        XStreamDataFormat xstreamDataFormat = ( XStreamDataFormat )dformatDefinition;
                        if ( "json".equals( xstreamDataFormat.getDriver() )) {
                            dformatDefinition =  XStreamJson.newJSonMarshaller( xstreamDataFormat );;    
                        } else {
                            dformatDefinition = XStreamXml.newXStreamMarshaller( (XStreamDataFormat) dformatDefinition );    
                        }
                        
                    } 
                    m.setDataFormatType( dformatDefinition ); // repoint the marshaller, if it was cloned
                    
                    
                } else if ( child instanceof UnmarshalDefinition ) {
                    UnmarshalDefinition m = (UnmarshalDefinition) child;
                    
                    DataFormatDefinition dformatDefinition = m.getDataFormatType();
                    
                    if ( dformatDefinition == null ) {
                        String ref = m.getRef();
                        if ( "json".equals( ref ) ) {
                            dformatDefinition = new XStreamDataFormat();
                            ((XStreamDataFormat)dformatDefinition).setDriver( "json" );
                        } else if ( "xstream".equals( ref ) ) {
                            dformatDefinition = new XStreamDataFormat();
                        } else if ( "jaxb".equals( ref ) ) {
                            dformatDefinition = new JaxbDataFormat();
                        } else {
                            dformatDefinition = routeContext.getCamelContext().resolveDataFormatDefinition(ref);
                        }
                    }
                    
                    // always clone before changing
                    dformatDefinition = new FastCloner().deepClone( dformatDefinition );                                       
                    
                    // Augment the Jaxb DataFormatDefinition, but clone first so we don't alter the original.
                    if ( dformatDefinition instanceof JaxbDataFormat ) {
                        dformatDefinition = augmentJaxbDataFormatDefinition( (JaxbDataFormat) dformatDefinition );                    
                    } else if ( dformatDefinition instanceof XStreamDataFormat ) { 
                        XStreamDataFormat xstreamDataFormat = ( XStreamDataFormat )dformatDefinition;
                        if ( "json".equals( xstreamDataFormat.getDriver() )) {
                            dformatDefinition =  XStreamJson.newJSonMarshaller( xstreamDataFormat );;    
                        } else {
                            dformatDefinition = XStreamXml.newXStreamMarshaller( (XStreamDataFormat) dformatDefinition );    
                        }
                    }
                     
                    m.setDataFormatType( dformatDefinition ); // repoint the marshaller, if it was cloned                    
                }
            }
            
            for ( Iterator<ProcessorDefinition> it = nav.getOutputs().iterator(); it.hasNext(); ) {
                ProcessorDefinition child = it.next();
                augmentNodes( routeContext,
                              child,
                              visited );
            }
        }        
    }

    private ToDefinition getDroolsNode(ProcessorDefinition nav) {
        if ( !nav.getOutputs().isEmpty() ) {
            List<ProcessorDefinition> children = nav.getOutputs();
            for ( ProcessorDefinition child : children ) {
                if ( child instanceof ToDefinition ) {
                    ToDefinition to = (ToDefinition) child;
                    if ( to.getUri().trim().startsWith( "drools:" ) ) {
                        return to;
                    }
                }
                getDroolsNode( child );
            }
        }
        return null;
    }
    
    /** 
     * Clones the passed JaxbDataFormat and then augments it with with Drools related namespaces
     * 
     * @param jaxbDataFormat
     * @return
     */
    public static JaxbDataFormat augmentJaxbDataFormatDefinition(JaxbDataFormat jaxbDataFormat) {
        Set<String> set = new HashSet<String>();

        for ( String clsName : JAXB_ANNOTATED_CMD ) {
            set.add( clsName.substring( 0,
                                        clsName.lastIndexOf( '.' ) ) );
        }

        StringBuilder sb = new StringBuilder();       
        sb.append( jaxbDataFormat.getContextPath() );
        sb.append( ":" );
        for ( String pkgName : set ) {
            sb.append( pkgName );
            sb.append( ':' );
        }

        jaxbDataFormat.setContextPath( sb.toString() );
        return jaxbDataFormat;
    }
    
    public static class DroolsClientProcessor
    implements
    Processor {
        
        private Processor processor;
        
        

        public DroolsClientProcessor(Processor processor) {
            this.processor = processor;
        }



        public void process(Exchange exchange) throws Exception {
            exchange.setPattern(ExchangePattern.InOut);
            Message inMessage = exchange.getIn();
            inMessage.setHeader(CxfConstants.CAMEL_CXF_RS_USING_HTTP_API, Boolean.TRUE);
            inMessage.setHeader(Exchange.HTTP_METHOD, "POST");
            inMessage.setHeader(Exchange.HTTP_PATH, "/execute");    
            inMessage.setHeader(Exchange.ACCEPT_CONTENT_TYPE, "text/plain");
            inMessage.setHeader(Exchange.CONTENT_TYPE, "text/plain");
            
            this.processor.process( exchange );
        }
        
    }
    
    public static class DroolsProcess
        implements
        Processor {

        private String         droolsUri;
        private DroolsEndpoint dep;
        private Processor      processor;

        public DroolsProcess(String droolsUri,
                             Processor processor) {
            this.droolsUri = droolsUri;
            this.processor = processor;
        }

        public void process(Exchange exchange) throws Exception {
            if ( dep == null ) {
                this.dep = exchange.getContext().getEndpoint( this.droolsUri,
                                                              DroolsEndpoint.class );
            }

            if ( dep == null ) {
                throw new RuntimeException( "Could not find DroolsEndPoint for uri=" + this.droolsUri );
            }

            ClassLoader originalClassLoader = null;
            try {
                originalClassLoader = Thread.currentThread().getContextClassLoader();
                
                CommandExecutor exec = dep.executor;
                if ( exec == null ) {
                    String lookup = exchange.getIn().getHeader( DroolsComponent.DROOLS_LOOKUP, String.class );
                    if ( StringUtils.isEmpty( lookup )  ) {
                        lookup = dep.getLookup( exchange.getIn().getBody( String.class ) );
                    }
                    
                    if ( StringUtils.isEmpty( lookup )  ) {
                        throw new RuntimeException( "No Executor defined and no lookup information available for uri " + this.dep.getEndpointUri()  );
                    }

                    exec = dep.getCommandExecutor( lookup );
                }


                if ( exec == null ) {
                    throw new RuntimeException( "CommandExecutor cannot be found for uri " + this.dep.getEndpointUri() );
                }

                ClassLoader localClassLoader = dep.getClassLoader( exec );
                if ( localClassLoader == null ) {
                    throw new RuntimeException( "CommandExecutor Classloader cannot be null for uri " + this.dep.getEndpointUri() );
                }

                // Set the classloader to the one used by the CommandExecutor
                Thread.currentThread().setContextClassLoader( localClassLoader );
                ExecutionNodePipelineContextImpl context = new ExecutionNodePipelineContextImpl( dep.node,
                                                                                                 localClassLoader );
                context.setCommandExecutor( exec );

                exchange.setProperty( "drools-context",
                                      context );

                processor.process( exchange );
            } finally {
                Thread.currentThread().setContextClassLoader( originalClassLoader );
            }
        }
    }

    public static final String[] JAXB_ANNOTATED_CMD = {BatchExecutionCommand.class.getName(), SetGlobalCommand.class.getName(), GetGlobalCommand.class.getName(), FireAllRulesCommand.class.getName(), InsertElementsCommand.class.getName(),
            InsertObjectCommand.class.getName(), ModifyCommand.class.getName(), SetterImpl.class.getName(), QueryCommand.class.getName(), RetractCommand.class.getName(), AbortWorkItemCommand.class.getName(), SignalEventCommand.class.getName(),
            StartProcessCommand.class.getName(), BatchExecutionCommand.class.getName(), ExecutionResultImpl.class.getName(), DefaultFactHandle.class.getName(), JaxbListWrapper.class.getName(), DisconnectedFactHandle.class.getName(),
            FlatQueryResults.class.getName(), CompleteWorkItemCommand.class.getName(), GetObjectsCommand.class.getName()};

}