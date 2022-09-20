/*
       Copyright 2017-2021 IBM Corp All Rights Reserved

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

package com.ibm.hybrid.cloud.sample.stocktrader.broker.client;

import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Feedback;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.Portfolio;
import com.ibm.hybrid.cloud.sample.stocktrader.broker.json.WatsonInput;

import jakarta.enterprise.context.ApplicationScoped;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;

import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;


@ApplicationPath("/")
@Path("/")
@ApplicationScoped
@RegisterRestClient
/** mpRestClient "remote" interface for the Portfolio microservice */
public interface PortfolioClient {
	@GET
	@Path("/")
	@Produces(MediaType.APPLICATION_JSON)
	public Portfolio[] getPortfolios(@HeaderParam("Authorization") String jwt);

	@POST
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	public Portfolio createPortfolio(@HeaderParam("Authorization") String jwt, @PathParam("owner") String owner, @QueryParam("accountID") String accountID);

	@GET
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	public Portfolio getPortfolio(@HeaderParam("Authorization") String jwt, @PathParam("owner") String owner, @QueryParam("immutable") boolean immutable);

	@PUT
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	public Portfolio updatePortfolio(@HeaderParam("Authorization") String jwt, @PathParam("owner") String owner, @QueryParam("symbol") String symbol, @QueryParam("shares") int shares, @QueryParam("commission") double commission);

	@DELETE
	@Path("/{owner}")
	@Produces(MediaType.APPLICATION_JSON)
	public Portfolio deletePortfolio(@HeaderParam("Authorization") String jwt, @PathParam("owner") String owner);
}
