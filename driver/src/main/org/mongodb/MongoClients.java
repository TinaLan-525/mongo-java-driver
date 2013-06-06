/*
 * Copyright (c) 2008 - 2013 10gen, Inc. <http://10gen.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.mongodb;

import org.mongodb.annotations.ThreadSafe;
import org.mongodb.connection.DefaultClusterableServerFactory;
import org.mongodb.connection.DefaultConnectionSettings;
import org.mongodb.connection.DefaultMultiServerCluster;
import org.mongodb.connection.DefaultSingleServerCluster;
import org.mongodb.connection.PowerOfTwoByteBufferPool;
import org.mongodb.connection.SSLSettings;
import org.mongodb.connection.ServerAddress;

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

@ThreadSafe
public final class MongoClients {
    public static MongoClient create(final ServerAddress serverAddress) {
        return create(serverAddress, MongoClientOptions.builder().build());
    }

    public static MongoClient create(final ServerAddress serverAddress, final MongoClientOptions options) {
        return new MongoClientImpl(options, new DefaultSingleServerCluster(serverAddress,
                getClusterableServerFactory(Collections.<MongoCredential>emptyList(), options)));
    }


    public static MongoClient create(final List<ServerAddress> seedList) {
        return create(seedList, MongoClientOptions.builder().build());
    }

    public static MongoClient create(final List<ServerAddress> seedList, final MongoClientOptions options) {
        return new MongoClientImpl(options, new DefaultMultiServerCluster(seedList,
                getClusterableServerFactory(Collections.<MongoCredential>emptyList(), options)));
    }

    public static MongoClient create(final MongoClientURI mongoURI) throws UnknownHostException {
        return create(mongoURI, mongoURI.getOptions());
    }

    public static MongoClient create(final MongoClientURI mongoURI, final MongoClientOptions options) throws UnknownHostException {
        if (mongoURI.getHosts().size() == 1) {
            return new MongoClientImpl(options, new DefaultSingleServerCluster(new ServerAddress(mongoURI.getHosts().get(0)),
                    getClusterableServerFactory(mongoURI.getCredentialList(), options)));
        }
        else {
            List<ServerAddress> seedList = new ArrayList<ServerAddress>();
            for (String cur : mongoURI.getHosts()) {
                seedList.add(new ServerAddress(cur));
            }
            return new MongoClientImpl(options, new DefaultMultiServerCluster(seedList,
                    getClusterableServerFactory(mongoURI.getCredentialList(), options)));
        }
    }

    private MongoClients() {
    }

    private static DefaultClusterableServerFactory getClusterableServerFactory(final List<MongoCredential> credentialList,
                                                                               final MongoClientOptions options) {
        return new DefaultClusterableServerFactory(
                credentialList, options,
                DefaultConnectionSettings.builder()
                        .connectTimeoutMS(options.getConnectTimeout())
                        .readTimeoutMS(options.getSocketTimeout())
                        .build(),
                SSLSettings.builder().enabled(options.isSSLEnabled()).build(),
                DefaultConnectionSettings.builder().build(),  // TODO: Allow configuration
                Executors.newScheduledThreadPool(3),
                new PowerOfTwoByteBufferPool()
        );
    }
}