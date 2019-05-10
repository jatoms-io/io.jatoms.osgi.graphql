package io.jatoms.graphql;

import static graphql.schema.GraphQLObjectType.newObject;
import static graphql.schema.GraphQLSchema.newSchema;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.component.annotations.ServiceScope;
import org.osgi.service.http.whiteboard.propertytypes.HttpWhiteboardServletPattern;

import graphql.execution.preparsed.NoOpPreparsedDocumentProvider;
import graphql.execution.preparsed.PreparsedDocumentProvider;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLType;
import graphql.servlet.AbstractGraphQLHttpServlet;
import graphql.servlet.DefaultExecutionStrategyProvider;
import graphql.servlet.DefaultGraphQLContextBuilder;
import graphql.servlet.DefaultGraphQLErrorHandler;
import graphql.servlet.DefaultGraphQLRootObjectBuilder;
import graphql.servlet.DefaultGraphQLSchemaProvider;
import graphql.servlet.ExecutionStrategyProvider;
import graphql.servlet.GraphQLContextBuilder;
import graphql.servlet.GraphQLErrorHandler;
import graphql.servlet.GraphQLInvocationInputFactory;
import graphql.servlet.GraphQLMutationProvider;
import graphql.servlet.GraphQLObjectMapper;
import graphql.servlet.GraphQLProvider;
import graphql.servlet.GraphQLQueryInvoker;
import graphql.servlet.GraphQLQueryProvider;
import graphql.servlet.GraphQLRootObjectBuilder;
import graphql.servlet.GraphQLSchemaProvider;
import graphql.servlet.GraphQLServletListener;
import graphql.servlet.GraphQLSubscriptionProvider;
import graphql.servlet.GraphQLTypesProvider;
import graphql.servlet.InstrumentationProvider;
import graphql.servlet.NoOpInstrumentationProvider;


@HttpWhiteboardServletPattern("/graphql")
@Component(service = {Servlet.class, HttpServlet.class} , scope = ServiceScope.PROTOTYPE,
    property = "jmx.objectname=graphql.servlet:type=graphql")
public class MyGraphQLServlet extends AbstractGraphQLHttpServlet {

    private final List<GraphQLQueryProvider> queryProviders = new ArrayList<>();
    private final List<GraphQLMutationProvider> mutationProviders = new ArrayList<>();
    private final List<GraphQLSubscriptionProvider> subscriptionProviders = new ArrayList<>();
    private final List<GraphQLTypesProvider> typesProviders = new ArrayList<>();

    private final GraphQLQueryInvoker queryInvoker;
    private final GraphQLInvocationInputFactory invocationInputFactory;
    private final GraphQLObjectMapper graphQLObjectMapper;

    private GraphQLContextBuilder contextBuilder = new DefaultGraphQLContextBuilder();
    private GraphQLRootObjectBuilder rootObjectBuilder = new DefaultGraphQLRootObjectBuilder();
    private ExecutionStrategyProvider executionStrategyProvider = new DefaultExecutionStrategyProvider();
    private InstrumentationProvider instrumentationProvider = new NoOpInstrumentationProvider();
    private GraphQLErrorHandler errorHandler = new DefaultGraphQLErrorHandler();
    private PreparsedDocumentProvider preparsedDocumentProvider = NoOpPreparsedDocumentProvider.INSTANCE;

    private GraphQLSchemaProvider schemaProvider;

	private ScheduledExecutorService executor;
	private ScheduledFuture<?> updateFuture;
    private int schemaUpdateDelay;

	@interface Config {
		int schema_update_delay() default 0;
	}

	@Activate
	public void activate(Config config) {
		this.schemaUpdateDelay = config.schema_update_delay();
		if (schemaUpdateDelay!=0)
			executor = Executors.newSingleThreadScheduledExecutor();
	}

	@Deactivate
	public void deactivate() {
		if (executor!=null) executor.shutdown();
	}

    @Override
    protected GraphQLQueryInvoker getQueryInvoker() {
        return queryInvoker;
    }

    @Override
    protected GraphQLInvocationInputFactory getInvocationInputFactory() {
        return invocationInputFactory;
    }

    @Override
    protected GraphQLObjectMapper getGraphQLObjectMapper() {
        return graphQLObjectMapper;
    }

    @Override
    protected boolean isAsyncServletMode() {
        return false;
    }

    public MyGraphQLServlet() {
        updateSchema();

        this.queryInvoker = GraphQLQueryInvoker.newBuilder()
            .withPreparsedDocumentProvider(this::getPreparsedDocumentProvider)
            .withInstrumentation(() -> this.getInstrumentationProvider().getInstrumentation())
            .withExecutionStrategyProvider(this::getExecutionStrategyProvider).build();

        this.invocationInputFactory = GraphQLInvocationInputFactory.newBuilder(this::getSchemaProvider)
            .withGraphQLContextBuilder(this::getContextBuilder)
            .withGraphQLRootObjectBuilder(this::getRootObjectBuilder)
            .build();

        this.graphQLObjectMapper = GraphQLObjectMapper.newBuilder()
            .withGraphQLErrorHandler(this::getErrorHandler)
            .build();
    }

    protected void updateSchema() {
    	if (schemaUpdateDelay==0) {
    		doUpdateSchema();
    	}
    	else {
    		if (updateFuture!=null)
    			updateFuture.cancel(true);

    		updateFuture = executor.schedule(new Runnable() {
				@Override
				public void run() {
					doUpdateSchema();
				}
			}, schemaUpdateDelay, TimeUnit.MILLISECONDS);
    	}
    }

    private void doUpdateSchema() {
        final GraphQLObjectType.Builder queryTypeBuilder = newObject().name("Query").description("Root query type");

        for (GraphQLQueryProvider provider : queryProviders) {
            if (provider.getQueries() != null && !provider.getQueries().isEmpty()) {
                provider.getQueries().forEach(queryTypeBuilder::field);
            }
        }

        final Set<GraphQLType> types = new HashSet<>();
        for (GraphQLTypesProvider typesProvider : typesProviders) {
            types.addAll(typesProvider.getTypes());
        }

        GraphQLObjectType mutationType = null;

        if (!mutationProviders.isEmpty()) {
            final GraphQLObjectType.Builder mutationTypeBuilder = newObject().name("Mutation").description("Root mutation type");

            for (GraphQLMutationProvider provider : mutationProviders) {
                provider.getMutations().forEach(mutationTypeBuilder::field);
            }

            if (!mutationTypeBuilder.build().getFieldDefinitions().isEmpty()) {
                mutationType = mutationTypeBuilder.build();
            }
        }

        GraphQLObjectType subscriptionType = null;

        if (!subscriptionProviders.isEmpty()) {
            final GraphQLObjectType.Builder subscriptionTypeBuilder = newObject().name("Subscription").description("Root subscription type");

            for (GraphQLSubscriptionProvider provider : subscriptionProviders) {
                provider.getSubscriptions().forEach(subscriptionTypeBuilder::field);
            }

            if (!subscriptionTypeBuilder.build().getFieldDefinitions().isEmpty()) {
                subscriptionType = subscriptionTypeBuilder.build();
            }
        }

        this.schemaProvider = new DefaultGraphQLSchemaProvider(newSchema().query(queryTypeBuilder.build())
                .mutation(mutationType)
                .subscription(subscriptionType)
                .additionalTypes(types)
                .build());
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void bindProvider(GraphQLProvider provider) {
        if (provider instanceof GraphQLQueryProvider) {
            queryProviders.add((GraphQLQueryProvider) provider);
        }
        if (provider instanceof GraphQLMutationProvider) {
            mutationProviders.add((GraphQLMutationProvider) provider);
        }
        if (provider instanceof GraphQLSubscriptionProvider) {
            subscriptionProviders.add((GraphQLSubscriptionProvider) provider);
        }
        if (provider instanceof GraphQLTypesProvider) {
            typesProviders.add((GraphQLTypesProvider) provider);
        }
        updateSchema();
    }
    public void unbindProvider(GraphQLProvider provider) {
        if (provider instanceof GraphQLQueryProvider) {
            queryProviders.remove(provider);
        }
        if (provider instanceof GraphQLMutationProvider) {
            mutationProviders.remove(provider);
        }
        if (provider instanceof GraphQLSubscriptionProvider) {
            subscriptionProviders.remove(provider);
        }
        if (provider instanceof GraphQLTypesProvider) {
            typesProviders.remove(provider);
        }
        updateSchema();
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void bindQueryProvider(GraphQLQueryProvider queryProvider) {
        queryProviders.add(queryProvider);
        updateSchema();
    }
    public void unbindQueryProvider(GraphQLQueryProvider queryProvider) {
        queryProviders.remove(queryProvider);
        updateSchema();
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void bindMutationProvider(GraphQLMutationProvider mutationProvider) {
        mutationProviders.add(mutationProvider);
        updateSchema();
    }
    public void unbindMutationProvider(GraphQLMutationProvider mutationProvider) {
        mutationProviders.remove(mutationProvider);
        updateSchema();
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void bindSubscriptionProvider(GraphQLSubscriptionProvider subscriptionProvider) {
        subscriptionProviders.add(subscriptionProvider);
        updateSchema();
    }
    public void unbindSubscriptionProvider(GraphQLSubscriptionProvider subscriptionProvider) {
        subscriptionProviders.remove(subscriptionProvider);
        updateSchema();
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void bindTypesProvider(GraphQLTypesProvider typesProvider) {
        typesProviders.add(typesProvider);
        updateSchema();
    }
    public void unbindTypesProvider(GraphQLTypesProvider typesProvider) {
        typesProviders.remove(typesProvider);
        updateSchema();
    }

    @Reference(cardinality = ReferenceCardinality.MULTIPLE, policy = ReferencePolicy.DYNAMIC)
    public void bindServletListener(GraphQLServletListener listener) {
        this.addListener(listener);
    }
    public void unbindServletListener(GraphQLServletListener listener) {
        this.removeListener(listener);
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setContextProvider(GraphQLContextBuilder contextBuilder) {
        this.contextBuilder = contextBuilder;
    }
    public void unsetContextProvider(GraphQLContextBuilder contextBuilder) {
        this.contextBuilder = new DefaultGraphQLContextBuilder();
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy = ReferencePolicy.DYNAMIC)
    public void setRootObjectBuilder(GraphQLRootObjectBuilder rootObjectBuilder) {
        this.rootObjectBuilder = rootObjectBuilder;
    }
    public void unsetRootObjectBuilder(GraphQLRootObjectBuilder rootObjectBuilder) {
        this.rootObjectBuilder = new DefaultGraphQLRootObjectBuilder();
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy= ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void setExecutionStrategyProvider(ExecutionStrategyProvider provider) {
        executionStrategyProvider = provider;
    }
    public void unsetExecutionStrategyProvider(ExecutionStrategyProvider provider) {
        executionStrategyProvider = new DefaultExecutionStrategyProvider();
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy= ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void setInstrumentationProvider(InstrumentationProvider provider) {
        instrumentationProvider = provider;
    }
    public void unsetInstrumentationProvider(InstrumentationProvider provider) {
        instrumentationProvider = new NoOpInstrumentationProvider();
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy= ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void setErrorHandler(GraphQLErrorHandler errorHandler) {
        this.errorHandler = errorHandler;
    }
    public void unsetErrorHandler(GraphQLErrorHandler errorHandler) {
        this.errorHandler = new DefaultGraphQLErrorHandler();
    }

    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policy= ReferencePolicy.DYNAMIC, policyOption = ReferencePolicyOption.GREEDY)
    public void setPreparsedDocumentProvider(PreparsedDocumentProvider preparsedDocumentProvider) {
        this.preparsedDocumentProvider = preparsedDocumentProvider;
    }
    public void unsetPreparsedDocumentProvider(PreparsedDocumentProvider preparsedDocumentProvider) {
        this.preparsedDocumentProvider = NoOpPreparsedDocumentProvider.INSTANCE;
    }

    public GraphQLContextBuilder getContextBuilder() {
        return contextBuilder;
    }

    public GraphQLRootObjectBuilder getRootObjectBuilder() {
        return rootObjectBuilder;
    }

    public ExecutionStrategyProvider getExecutionStrategyProvider() {
        return executionStrategyProvider;
    }

    public InstrumentationProvider getInstrumentationProvider() {
        return instrumentationProvider;
    }

    public GraphQLErrorHandler getErrorHandler() {
        return errorHandler;
    }

    public PreparsedDocumentProvider getPreparsedDocumentProvider() {
        return preparsedDocumentProvider;
    }

    public GraphQLSchemaProvider getSchemaProvider() {
        return schemaProvider;
    }
}