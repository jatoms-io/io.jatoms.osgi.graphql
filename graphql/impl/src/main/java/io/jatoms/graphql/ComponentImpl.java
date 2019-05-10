package io.jatoms.graphql;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.osgi.service.component.annotations.Component;

import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLType;
import graphql.servlet.GraphQLMutationProvider;
import graphql.servlet.GraphQLQueryProvider;
import graphql.servlet.GraphQLTypesProvider;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.*;

@Component(
        name="ExampleGraphQLProvider",
        immediate=true
)
public class ComponentImpl implements GraphQLQueryProvider, GraphQLMutationProvider, GraphQLTypesProvider {

    public Collection<GraphQLFieldDefinition> getQueries() {
        List<GraphQLFieldDefinition> fieldDefinitions = new ArrayList<GraphQLFieldDefinition>();
        fieldDefinitions.add(newFieldDefinition()
                .type(GraphQLString)
                .name("hello")
                .staticValue("world")
                .build());
        return fieldDefinitions;
    }

    public Collection<GraphQLFieldDefinition> getMutations() {
        return new ArrayList<GraphQLFieldDefinition>();
    }

    public Collection<GraphQLType> getTypes() {

        List<GraphQLType> types = new ArrayList<GraphQLType>();
        return types;
    }
}
