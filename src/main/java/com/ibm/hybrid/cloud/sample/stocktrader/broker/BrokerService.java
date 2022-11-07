/*
       Copyright 2020-2021 IBM Corp All Rights Reserved

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.ibm.hybrid.cloud.sample.stocktrader.broker;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.AccountClient;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.PortfolioClient;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.TradeHistoryClient;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.client.graphql.AccountGraphQLClient;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Account;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Broker;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Feedback;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Portfolio;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.WatsonInput;


import java.io.PrintWriter;
import java.io.StringWriter;

//Logging (JSR 47)
import java.util.*;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

//CDI 2.0
import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import jakarta.inject.Inject;
import jakarta.enterprise.context.RequestScoped;

//mpConfig 1.3

//mpJWT 1.1
import io.smallrye.graphql.client.typesafe.api.TypesafeGraphQLClientBuilder;
import org.eclipse.microprofile.auth.LoginConfig;

//mpRestClient 1.3
import org.eclipse.microprofile.metrics.annotation.Timed;
import org.eclipse.microprofile.rest.client.inject.RestClient;

//Servlet 4.0
import jakarta.servlet.http.HttpServletRequest;

//JAX-RS 2.1 (JSR 339)
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Path;

@ApplicationPath("/")
@Path("/")
@LoginConfig(authMethod = "MP-JWT", realmName = "jwt-jaspi")
@RequestScoped //enable interceptors like @Transactional (note you need a WEB-INF/beans.xml in your war)
/** This microservice is the controller in a model-view-controller architecture, doing the routing and
 *  combination of results from other microservices.  Note that the Portfolio microservice it calls is
 *  mandatory, whereas the Account and TradeHistory microservices are optional.
 */
public class BrokerService extends Application {
    private static Logger logger = Logger.getLogger(BrokerService.class.getName());

    private static final double DONT_RECALCULATE = -1.0;

    private static boolean useAccount = false;
    private static boolean useS3 = false;
    private static boolean useCQRS = false;
    private static boolean initialized = false;
    private static boolean staticInitialized = false;

    private @Inject
    @RestClient PortfolioClient portfolioClient;
    private @Inject
    @RestClient AccountClient accountClient;
    private @Inject
    @RestClient TradeHistoryClient tradeHistoryClient;

    // rtclauss inject GraphQLClient for account
    // Broken in liberty
    // private @Inject AccountGraphQLClient accountGraphQLClient;

    AccountGraphQLClient accountGraphQLClient = TypesafeGraphQLClientBuilder.newBuilder()
            .build(AccountGraphQLClient.class);

    // OpenTracing tracer
    private @Inject Tracer tracer;


    // Override ODM Client URL if secret is configured to provide URL
    static {
        useAccount = Boolean.parseBoolean(System.getenv("ACCOUNT_ENABLED"));
        logger.info("Account microservice enabled: " + useAccount);

        useS3 = Boolean.parseBoolean(System.getenv("S3_ENABLED"));
        logger.info("S3 enabled: " + useS3);

        useCQRS = Boolean.parseBoolean(System.getenv("CQRS_ENABLED"));
        logger.info("CQRS enabled: " + useCQRS);

        String mpUrlPropName = PortfolioClient.class.getName() + "/mp-rest/url";
        String urlFromEnv = System.getenv("PORTFOLIO_URL");
        if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
            logger.info("Using Portfolio URL from config map: " + urlFromEnv);
            System.setProperty(mpUrlPropName, urlFromEnv);
        } else {
            logger.info("Portfolio URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
        }

        mpUrlPropName = AccountClient.class.getName() + "/mp-rest/url";
        urlFromEnv = System.getenv("ACCOUNT_URL");
        if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
            logger.info("Using Account URL from config map: " + urlFromEnv);
            System.setProperty(mpUrlPropName, urlFromEnv);
        } else {
            logger.info("Account URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
        }

        // @rtclauss
        mpUrlPropName = AccountGraphQLClient.class.getName() + "/mp-graphql/url";
        //TODO do I need to change this?
        urlFromEnv = System.getenv("ACCOUNT_GRAPHQL_URL");
        if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
            logger.info("Using Account GraphQL URL from config map: " + urlFromEnv);
            System.setProperty(mpUrlPropName, urlFromEnv);
        } else {
            logger.info("Account GraphQL URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
        }

        mpUrlPropName = TradeHistoryClient.class.getName() + "/mp-rest/url";
        urlFromEnv = System.getenv("TRADE_HISTORY_URL");
        if ((urlFromEnv != null) && !urlFromEnv.isEmpty()) {
            logger.info("Using Trade History URL from config map: " + urlFromEnv);
            System.setProperty(mpUrlPropName, urlFromEnv);
        } else {
            logger.info("Trade History URL not found from env var from config map, so defaulting to value in jvm.options: " + System.getProperty(mpUrlPropName));
        }
    }

    @GET
    @Path("/")
    @Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    @Timed(name = "getBrokersTimer", description = "How long does it take retrieve all portfolio data from Portfolio and Account")
    public Broker[] getBrokers(@Context HttpServletRequest request) {
        String jwt = request.getHeader("Authorization");

        if (useCQRS) {
            logger.info("getBrokers: Placeholder for when CQRS support is added");
        }

        logger.fine("Calling PortfolioClient.getPortfolios()");
        Portfolio[] portfolios;
        Span getAllPortfoliosSpan = tracer.buildSpan("BrokerService.getBrokers portfolioClient.getPortfolios(jwt)").start();
        try (Scope childScope = tracer.scopeManager().activate(getAllPortfoliosSpan)) {
            portfolios = portfolioClient.getPortfolios(jwt);
        } finally {
            getAllPortfoliosSpan.finish();
        }

        int portfolioCount = 0;
        Broker[] brokers = null;
        Set<Broker> brokersSet = new HashSet<>();
        if (portfolios != null) {
            portfolioCount = portfolios.length;
            int accountCount = 0;

            brokers = new Broker[portfolioCount];
//			rtclauss 9/8/2022 for graphql
//			Account[] accounts = new Account[portfolioCount];
            List<Account> accounts = new ArrayList<>(portfolioCount);

            if (useAccount) try {

                //			rtclauss 9/8/2022 for graphql
//				logger.fine("Calling AccountClient.getAccounts()");
//				accounts = accountClient.getAccounts(jwt);
//				accountCount = accounts.length;
//				accounts = accountGraphQLClient.getAllAccounts(jwt);
                //jwt = "Bearer " + jwt;
                logger.fine("Calling GraphQL accountGraphQLClient.getAccounts(jwt)");
                Span getAllAccountsSpan = tracer.buildSpan("BrokerService.getBrokers accountGraphQLClient.getAccounts(jwt)").start();
                try (Scope childScope = tracer.scopeManager().activate(getAllAccountsSpan)) {
                    accounts = accountGraphQLClient.allAccounts(jwt);
                } finally {
                    getAllAccountsSpan.finish();
                }
                accountCount = accounts.size();
            } catch (Throwable t) {
                logException(t);
            }

            // Match up the accounts and portfolios
            // TODO: Pagination should reduce the amount of work to do here
            Span mapPortfoliosToAccountsSpan = tracer.buildSpan("BrokerService.getBrokers parallel Map portfolios to account").start();
            try (Scope childScope = tracer.scopeManager().activate(mapPortfoliosToAccountsSpan)) {
//			Map<String, Account> mapOfAccounts = Arrays.stream(accounts).collect(Collectors.toMap(Account::getId, account -> account));
//			Set<String> accountIds = Arrays.stream(accounts).map(Account::getId).collect(Collectors.toSet());
                Map<String, Account> mapOfAccounts = accounts.stream().collect(Collectors.toMap(Account::getId, account -> account));
                Set<String> accountIds = accounts.stream().map(Account::getId).collect(Collectors.toSet());

                brokersSet = Arrays.stream(portfolios)
                        .parallel()
                        .filter(portfolio -> accountIds.contains(portfolio.getAccountID()))
                        .map(portfolio -> {
                            String ownerId = portfolio.getAccountID();
                            // Don't log here, you'll get a NPE if you uncomment the following line
                            // logger.finer("Found account corresponding to the portfolio for " + owner);
                            return new Broker(portfolio, mapOfAccounts.get(ownerId));
                        })
                        .collect(Collectors.toSet());

                // Now handle the cases where there is no matching account-portfolio mapping
                brokersSet.addAll(Arrays.stream(portfolios)
                        .parallel()
                        .filter(Predicate.not(portfolio -> accountIds.contains(portfolio.getAccountID())))
                        .map(portfolio -> {
                            // Don't log here, you'll get a NPE
                            // logger.finer("Did not find account corresponding to the portfolio for " + owner);
                            return new Broker(portfolio, null);
                        })
                        .collect(Collectors.toSet()));
            } finally {
                mapPortfoliosToAccountsSpan.finish();
            }
        }

        logger.fine("Returning " + portfolioCount + " portfolios");

        if (brokersSet != null) {
            // brokers = brokersSet.stream().toArray(Broker[]::new);
            // Sort the list for deterministic order
            brokers = brokersSet.parallelStream().sorted(Comparator.comparing(Broker::getOwner)).toArray(Broker[]::new);
            return brokers;
        } else {
            return null;
        }
    }

    @GET
    @Path("/{page}/{pageSize}")
    @Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    @Timed(name = "getBrokersPaginationTimer", description = "How long does it take retrieve all pages of broker data from Portfolio and Account")
    public Broker[] getBrokersPagination(@PathParam("page") Integer page, @PathParam("pageSize") Integer pageSize, @Context HttpServletRequest request) {
        String jwt = request.getHeader("Authorization");

        if (useCQRS) {
            logger.info("getBrokers: Placeholder for when CQRS support is added");
        }

        logger.fine("Calling PortfolioClient.getPortfoliosByPage(page, pageSize)");
        List<Portfolio> portfolios;
        Span getAllPortfoliosSpan = tracer.buildSpan("BrokerService.getBrokers PortfolioClient.getPortfoliosByPage(page, pageSize)").start();
        try (Scope childScope = tracer.scopeManager().activate(getAllPortfoliosSpan)) {
            portfolios = portfolioClient.getPortfoliosByPage(jwt, page, pageSize);
        } finally {
            getAllPortfoliosSpan.finish();
        }

        int portfolioCount = 0;
        Broker[] brokers = null;
        Set<Broker> brokersSet = new HashSet<>();
        if (portfolios != null) {
            portfolioCount = portfolios.size();
            int accountCount = 0;

            brokers = new Broker[portfolioCount];
            List<Account> accounts = new ArrayList<>(portfolioCount);

            if (useAccount) try {
                var owners = portfolios.parallelStream().map(Portfolio::getOwner).collect(Collectors.toList());
                logger.fine("Calling GraphQL accountGraphQLClient.getAccountsByOwner(jwt, owners)");
                Span getAllAccountsSpan = tracer.buildSpan("BrokerService.getBrokers accountGraphQLClient.getAccountsByOwner(jwt, owners)").start();
                try (Scope childScope = tracer.scopeManager().activate(getAllAccountsSpan)) {
                    accounts = accountGraphQLClient.getAccountsByOwner(jwt, owners);
                } finally {
                    getAllAccountsSpan.finish();
                }
                accountCount = accounts.size();
            } catch (Throwable t) {
                logException(t);
            }

            // Match up the accounts and portfolios
            Span mapPortfoliosToAccountsSpan = tracer.buildSpan("BrokerService.getBrokers parallel Map portfolios to account").start();
            try (Scope childScope = tracer.scopeManager().activate(mapPortfoliosToAccountsSpan)) {
                Map<String, Account> mapOfAccounts = accounts.stream().collect(Collectors.toMap(Account::getId, account -> account));
                Set<String> accountIds = accounts.stream().map(Account::getId).collect(Collectors.toSet());

                brokersSet = portfolios.stream()
                        .parallel()
                        .filter(portfolio -> accountIds.contains(portfolio.getAccountID()))
                        .map(portfolio -> {
                            String ownerId = portfolio.getAccountID();
                            // Don't log here, you'll get a NPE if you uncomment the following line
                            // logger.finer("Found account corresponding to the portfolio for " + owner);
                            return new Broker(portfolio, mapOfAccounts.get(ownerId));
                        })
                        .collect(Collectors.toSet());

                // Now handle the cases where there is no matching account-portfolio mapping
                brokersSet.addAll(portfolios.stream()
                        .parallel()
                        .filter(Predicate.not(portfolio -> accountIds.contains(portfolio.getAccountID())))
                        .map(portfolio -> {
                            // Don't log here, you'll get a NPE
                            // logger.finer("Did not find account corresponding to the portfolio for " + owner);
                            return new Broker(portfolio, null);
                        })
                        .collect(Collectors.toSet()));
            } finally {
                mapPortfoliosToAccountsSpan.finish();
            }
        }

        logger.fine("Returning " + portfolioCount + " portfolios");

        if (brokersSet != null) {
            // brokers = brokersSet.stream().toArray(Broker[]::new);
            // Sort the list for deterministic order
            brokers = brokersSet.parallelStream().sorted(Comparator.comparing(Broker::getOwner)).toArray(Broker[]::new);
            return brokers;
        } else {
            return null;
        }
    }

    @POST
    @Path("/{owner}")
    @Produces(MediaType.APPLICATION_JSON)
    //	@RolesAllowed({"StockTrader"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    @Timed(name = "createBrokersTimer", description = "How long does it take create a portfolio in Portfolio and Account")
    public Broker createBroker(@PathParam("owner") String owner, @Context HttpServletRequest request) {
        Broker broker = null;
        Portfolio portfolio = null;
        String jwt = request.getHeader("Authorization");

        Account account = null;
        String accountID = null;
        Span createAccountDBSpan = tracer.buildSpan("BrokerService.createBroker accountGraphQLClient.createAccount(jwt, owner)").start();
        try (Scope childScope = tracer.scopeManager().activate(createAccountDBSpan)) {
            if (useAccount) try {
                // rtclauss 9/8/22
                // This is the REST API
                // logger.fine("Calling AccountClient.createAccount()");
                //  account = accountClient.createAccount(jwt, owner);
                // This is the GraphQL API
                logger.fine("Calling GraphQL accountGraphQLClient.createAccount(jwt, " + owner + ")");
                account = accountGraphQLClient.createAccount(jwt, owner);
                if (account != null) accountID = account.getId();
            } catch (Throwable t) {
                logException(t);
            }
        } finally {
            createAccountDBSpan.finish();
        }

        logger.fine("Calling PortfolioClient.createPortfolio()");
        Span createPortfolioDBSpan = tracer.buildSpan("BrokerService.createBroker portfolioClient.createPortfolio(jwt, owner, accountID)").start();
        try (Scope childScope = tracer.scopeManager().activate(createAccountDBSpan)) {
            portfolio = portfolioClient.createPortfolio(jwt, owner, accountID);
        } finally {
            createPortfolioDBSpan.finish();
        }

        String answer = "broker";
        if (portfolio != null) {
            broker = new Broker(portfolio, account);
        } else {
            answer = "null";
        }
        logger.fine("Returning " + answer);

        return broker;
    }

    @GET
    @Path("/{owner}")
    @Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader", "StockViewer"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    @Timed(name = "getBrokerTimer", description = "How long does it take retrieve a single portfolio from Portfolio and Account")
    public Broker getBroker(@PathParam("owner") String owner, @Context HttpServletRequest request) {
        Broker broker = null;
        Portfolio portfolio = null;
        String jwt = request.getHeader("Authorization");

        if (useCQRS) {
            logger.info("getBroker: Placeholder for when CQRS support is added");
        }

        logger.fine("Calling PortfolioClient.getPortfolio()");
        Span getPortfolioSpan = tracer.buildSpan("BrokerService.getBroker portfolioClient.getPortfolio(jwt, owner, false)").start();
        try (Scope childScope = tracer.scopeManager().activate(getPortfolioSpan)) {
            portfolio = portfolioClient.getPortfolio(jwt, owner, false);
        } finally {
            getPortfolioSpan.finish();
        }

        String answer = "broker";
        if (portfolio != null) {
            logger.finest("Retrieved portfolio: " + portfolio);
            String accountID = portfolio.getAccountID();
            double total = portfolio.getTotal();
            Account account = null;
            if (useAccount) try {
//				logger.fine("Calling REST AccountClient.getAccount()");
//				account = accountClient.getAccount(jwt, accountID, total);
                logger.fine("Calling GraphQL accountGraphQLClient.getAccount(jwt, " + accountID + ")");
                //TODO move this up into the parent try
                Span getAccountSpan = tracer.buildSpan("BrokerService.getBroker accountGraphQLClient.getAccount(jwt, accountID, total)").start();
                try (Scope childScope = tracer.scopeManager().activate(getAccountSpan)) {
                    account = accountGraphQLClient.retrieveAccountById(jwt, accountID);
                    if (account == null) logger.warning("Account not found for " + owner);
                } finally {
                    getAccountSpan.finish();
                }
            } catch (Throwable t) {
                logException(t);
            }
            broker = new Broker(portfolio, account);
        } else {
            answer = "null";
        }
        logger.fine("Returning " + answer);

        return broker;
    }

    @GET
    @Path("/{owner}/returns")
    @Produces(MediaType.TEXT_PLAIN)
    @Timed(name = "getPortfolioReturnsTimer", description = "How long does it take retrieve all portfolio return data from Portfolio and TradeHistory")
    public String getPortfolioReturns(@PathParam("owner") String owner, @Context HttpServletRequest request) {
        String jwt = request.getHeader("Authorization");

        logger.fine("Getting portfolio returns");
        String result = "Unknown";
        Portfolio portfolio;

        Span getPortfolioSpan = tracer.buildSpan("BrokerService.getPortfolioReturns portfolioClient.getPortfolio(jwt, owner, true)").start();
        try (Scope childScope = tracer.scopeManager().activate(getPortfolioSpan)) {
            portfolio = portfolioClient.getPortfolio(jwt, owner, true); //throws a 404 exception if not present
        } finally {
            getPortfolioSpan.finish();
        }
        if (portfolio != null) {
            Double portfolioValue = portfolio.getTotal();
            Span getTradeHistorySpan = tracer.buildSpan("BrokerService.getPortfolioReturns tradeHistoryClient.getReturns(jwt, owner, portfolioValue)").start();
            try (Scope childScope = tracer.scopeManager().activate(getTradeHistorySpan)) {
                result = tradeHistoryClient.getReturns(jwt, owner, portfolioValue);
                logger.fine("Got portfolio returns for " + owner);
            } catch (Throwable t) {
                logger.info("Unable to invoke TradeHistory.  This is an optional microservice and the following exception is expected if it is not deployed");
                logException(t);
            } finally {
                getTradeHistorySpan.finish();
            }
        } else {
            logger.warning("Portfolio not found to get returns for " + owner);
        }
        return result;
    }

    @PUT
    @Path("/{owner}")
    @Produces(MediaType.APPLICATION_JSON)
    @Timed(name = "updateBrokerTimer", description = "How Long does it take to update Portfolio and Account")
//	@RolesAllowed({"StockTrader"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    public Broker updateBroker(@PathParam("owner") String owner, @QueryParam("symbol") String symbol, @QueryParam("shares") int shares, @Context HttpServletRequest request) {
        Broker broker = null;
        Account account = null;
        Portfolio portfolio = null;
        String jwt = request.getHeader("Authorization");

        double commission = 0.0;
        String accountID = null;

        Span getPortfolioAndAccountSpan = tracer.buildSpan("BrokerService.updateBroker get portfolio and get client").start();
        if (useAccount) try (Scope childScope = tracer.scopeManager().activate(getPortfolioAndAccountSpan)) {
            logger.fine("Calling PortfolioClient.getPortfolio() to get accountID in updateBroker()");
            Span getPortfolioSpan = tracer.buildSpan("BrokerService.updateBroker portfolioClient.getPortfolio(jwt, owner, false)").start();
            try (Scope childScope1 = tracer.scopeManager().activate(getPortfolioSpan)) {
                portfolio = portfolioClient.getPortfolio(jwt, owner, false); //throws a 404 if it doesn't exist
                accountID = portfolio.getAccountID();
            } finally {
                getPortfolioSpan.finish();
            }

            //@rtclauss
//			logger.fine("Calling AccountClient.getAccount() to get commission in updateBroker()");
//			account = accountClient.getAccount(jwt, accountID, DONT_RECALCULATE);
            logger.fine("Calling GraphQL accountGraphQLClient.retrieveAccountById(jwt, " + accountID + ") to get commission in updateBroker()");
            Span getAccountSpan = tracer.buildSpan("BrokerService.updateBroker accountGraphQLClient.getAccount(jwt, accountID, DONT_RECALCULATE)").start();
            try (Scope childScope1 = tracer.scopeManager().activate(getPortfolioSpan)) {
                account = accountGraphQLClient.retrieveAccountById(jwt, accountID);
                commission = account.getNextCommission();
            } finally {
                getAccountSpan.finish();
            }
        } catch (Throwable t) {
            logException(t);
        }

        logger.fine("Calling PortfolioClient.updatePortfolio()");
        Span updatePortfolioSpan = tracer.buildSpan("BrokerService.updateBroker portfolioClient.updatePortfolio(jwt, owner, symbol, shares, commission)").start();
        try (Scope childScope1 = tracer.scopeManager().activate(updatePortfolioSpan)) {
            portfolio = portfolioClient.updatePortfolio(jwt, owner, symbol, shares, commission);
        } finally {
            updatePortfolioSpan.finish();
        }

        String answer = "broker";
        if (portfolio != null) {
            double portfolioTotal = portfolio.getTotal();
            account = null;

            Span updateAccountSpan = tracer.buildSpan("BrokerService.updateBroker accountGraphQLClient.updateAccount(jwt, accountID, total)").start();
            if (useAccount) try (Scope childScope1 = tracer.scopeManager().activate(updatePortfolioSpan)) {
//				logger.fine("Calling AccountClient.updateAccount()");
//				account = accountClient.updateAccount(jwt, accountID, portfolioTotal);
                logger.fine("Calling GraphQL accountGraphQLClient.updateAccount(jwt, " + accountID + "," + portfolioTotal + ")");
                account = accountGraphQLClient.updateAccount(jwt, accountID, portfolioTotal);
            } catch (Throwable t) {
                logException(t);
            } finally {
                updateAccountSpan.finish();
            }
            broker = new Broker(portfolio, account);
        } else {
            answer = "null";
        }
        logger.fine("Returning " + answer);

        return broker;
    }

    @DELETE
    @Path("/{owner}")
    @Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    @Timed(name = "deleteBrokerTimer", description = "How long does it take delete a portfolio's data from Portfolio and Account")
    public Broker deleteBroker(@PathParam("owner") String owner, @Context HttpServletRequest request) {
        Broker broker = null;
        Portfolio portfolio = null;
        String jwt = request.getHeader("Authorization");

        logger.fine("Calling PortfolioClient.deletePortfolio()");
        Span deletePortfolioSpan = tracer.buildSpan("BrokerService.deleteBroker portfolioClient.deletePortfolio(jwt, owner)").start();
        try (Scope childScope1 = tracer.scopeManager().activate(deletePortfolioSpan)) {
            portfolio = portfolioClient.deletePortfolio(jwt, owner);
        } finally {
            deletePortfolioSpan.finish();
        }

        String answer = "broker";
        if (portfolio != null) {
            Account account = null;

            Span deleteAccountSpan = tracer.buildSpan("BrokerService.deleteBroker accountGraphQLClient.deleteAccount(jwt, accountID)").start();
            if (useAccount) try (Scope childScope1 = tracer.scopeManager().activate(deleteAccountSpan)) {
                String accountID = portfolio.getAccountID();
//				logger.fine("Calling AccountClient.deleteAccount()");
//				account = accountClient.deleteAccount(jwt, accountID);
                logger.fine("Calling GraphQL accountGraphQLClient.deleteAccount(jwt, " + accountID + ")");
                account = accountGraphQLClient.deleteAccount(jwt, accountID);
            } catch (Throwable t) {
                logException(t);
            } finally {
                deleteAccountSpan.finish();
            }
            broker = new Broker(portfolio, account);
        } else {
            answer = "null";
        }
        logger.fine("Returning " + answer);

        return broker; //maybe this method should return void instead?
    }

    @POST
    @Path("/{owner}/feedback")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
//	@RolesAllowed({"StockTrader"}) //Couldn't get this to work; had to do it through the web.xml instead :(
    @Timed(name = "submitFeedbackTimer", description = "How long does it take submit feedback to Watson and update Account")
    public Feedback submitFeedback(@PathParam("owner") String owner, WatsonInput input, @Context HttpServletRequest request) {
        Feedback feedback = null;
        String jwt = request.getHeader("Authorization");

//		logger.fine("Calling AccountClient.submitFeedback()");
//		feedback = accountClient.submitFeedback(jwt, owner, input);
        logger.fine("Calling GraphQL accountGraphQLClient.submitFeedback(jwt, " + owner + ", " + input + ")");
        Span submitFeedbackSpan = tracer.buildSpan("BrokerService.submitFeedback accountClient.submitFeedback(jwt, owner, input)").start();
        try (Scope childScope1 = tracer.scopeManager().activate(submitFeedbackSpan)) {
            feedback = accountGraphQLClient.submitFeedback(jwt, owner, input);
        } finally {
            submitFeedbackSpan.finish();
        }
        String answer = "feedback";
        if (feedback == null) answer = "null";
        logger.fine("Returning " + answer);

        return feedback;
    }

    static void logException(Throwable t) {
        logger.warning(t.getClass().getName() + ": " + t.getMessage());

        //only log the stack trace if the level has been set to at least INFO
        if (logger.isLoggable(Level.INFO)) {
            StringWriter writer = new StringWriter();
            t.printStackTrace(new PrintWriter(writer));
            logger.info(writer.toString());
        }
    }
}
