package com.ibm.hybrid.cloud.sample.stocktrader.broker.client.graphql;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Account;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Feedback;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.WatsonInput;

import io.smallrye.graphql.client.typesafe.api.GraphQLClientApi;
import io.smallrye.graphql.client.typesafe.api.Header;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

import java.util.List;

@GraphQLClientApi
// rtclauss @AuthorizationHeader passes either a username/pass or bearer token that is defined via MP Config
// this would be useful for service-to-service calls, when there is no person user credentials needed.
//@AuthorizationHeader(type = AuthorizationHeader.Type.BEARER)
public interface AccountGraphQLClient {

    // TODO split into separate query/resolver and mutation clients?

    // the function name must match the value of @Query(name=value) (if name is used) or the name of the function
    // on the server OR you need to add the @Query(value) annotation.  For example, AccountQueries.getAllAccounts is annotated @Query("allAccounts") so the function
    // must be named `allAccounts` OR you should define it as:
    //     @Query("allAccounts") List<Account> allAccounts(@Header(name="Authorization") String jwt);
    // See https://smallrye.io/smallrye-graphql/2.0.0.RC9/typesafe-client-usage/
    @Query public List<Account> allAccounts(@Header(name = "Authorization") String jwt);

    @Query
    public List<Account> allAccountsByPage(@Header(name = "Authorization") String jwt, @Name("pageNumber") Integer pageNumber, @Name("pageSize") Integer pageSize);

    @Query("retrieveAccountsByOwner")
    public List<Account> getAccountsByOwner(@Header(name = "Authorization") String jwt, @Name("owners") List<String> owners);

    @Query
    public Account retrieveAccountById(@Header(name = "Authorization") String jwt, @Name("ownerId") String ownerId);

    @Query
    public List<Account> retrieveAccountByOwnerName(@Header(name = "Authorization") String jwt, @Name("owner") String ownerName);


    // Mutations
    // the function name must match the value of @Mutation(name=value) (if name is used) or the name of the function
    // on the server.  For example, AccountMutations.createAccount is not annotated @Mutation("newAccount") so the function
    // must be named `createAccount`
    @Mutation
    public Account createAccount(@Header(name = "Authorization") String jwt, @Name("ownerName") String ownerName);

    @Mutation
    public Feedback submitFeedback(@Header(name = "Authorization") String jwt, @Name("id") String id, @Name("watsonFeedback") WatsonInput watsonFeedback);

    @Mutation("deleteAccount")
    public Account deleteAccount(@Header(name = "Authorization") String jwt, @Name("id") String id);

    @Mutation
    public Account updateAccount(@Header(name = "Authorization") String jwt, @Name("id") String id, @Name("portfolioTotal") double portfolioTotal);
}
