/*
 * Copyright 2017 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.atlasdb.keyvalue.cassandra;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.cassandra.thrift.TokenRange;

import com.google.common.collect.Multimap;
import com.google.common.collect.Range;
import com.google.common.collect.RangeMap;

final class CassandraLogHelper {
    private CassandraLogHelper() {
        // Utility class.
    }

    static String host(InetSocketAddress host) {
        return host.getHostString();
    }

    static List<String> blacklistedHosts(Map<InetSocketAddress, Long> blacklistedHosts) {
        return blacklistedHosts.entrySet().stream()
                .map(blacklistedHostToBlacklistTime -> String.format("host: %s was blacklisted at %s",
                        host(blacklistedHostToBlacklistTime.getKey()),
                        blacklistedHostToBlacklistTime.getValue().longValue()))
                .collect(Collectors.toList());
    }

    static Collection<String> collectionOfHosts(Collection<InetSocketAddress> hosts) {
        return hosts.stream().map(CassandraLogHelper::host).collect(Collectors.toSet());
    }

    static List<String> tokenRangesToHost(Multimap<Set<TokenRange>, InetSocketAddress> tokenRangesToHost) {
        return tokenRangesToHost.entries().stream()
                .map(entry -> String.format("host %s has range %s",
                        entry.getKey().toString(),
                        host(entry.getValue())))
                .collect(Collectors.toList());
    }

    static List<String> tokenMap(
            RangeMap<CassandraClientPoolImpl.LightweightOppToken, List<InetSocketAddress>> tokenMap) {

        return tokenMap.asMapOfRanges().entrySet().stream()
                .map(rangeListToHostEntry -> String.format("range from %s to %s is on host %s",
                        getLowerEndpoint(rangeListToHostEntry.getKey()),
                        getUpperEndpoint(rangeListToHostEntry.getKey()),
                        CassandraLogHelper.collectionOfHosts(rangeListToHostEntry.getValue())))
                .collect(Collectors.toList());
    }

    private static String getLowerEndpoint(Range<CassandraClientPoolImpl.LightweightOppToken> range) {
        if (range.hasLowerBound()) {
            return "(no lower bound)";
        }
        return range.lowerEndpoint().toString();
    }

    private static String getUpperEndpoint(Range<CassandraClientPoolImpl.LightweightOppToken> range) {
        if (range.hasUpperBound()) {
            return "(no upper bound)";
        }
        return range.upperEndpoint().toString();
    }
}
