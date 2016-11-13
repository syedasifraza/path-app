/*
 * Copyright 2016-present Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.path.app;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.collections.functors.FalsePredicate;
import org.apache.commons.collections.functors.StringValueTransformer;
import org.onlab.packet.IpAddress;
import org.onlab.packet.MacAddress;
import org.onlab.packet.TpPort;
import org.onosproject.core.ApplicationId;
import org.onosproject.core.CoreService;
import org.onosproject.net.*;
import org.onosproject.net.config.basics.BasicLinkConfig;
import org.onosproject.net.device.DeviceService;
import org.onosproject.net.flow.*;
import org.onosproject.net.host.HostService;
import org.onosproject.net.statistic.Load;
import org.onosproject.net.statistic.StatisticService;
import org.onosproject.net.topology.LinkWeight;
import org.onosproject.net.topology.TopologyEdge;
import org.onosproject.net.topology.TopologyService;
import org.onosproject.rest.AbstractWebResource;


import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;


/**
 * Sample web resource. This is Optimal path selection application
 */
@Path("path")
public class AppWebResource extends AbstractWebResource {

    public static final int PRIORITY=10;
    public static final int TIME_OUT=30;
    /**
     * Get hello world greeting.
     *
     * @return 200 OK
     */
    @GET
    @Path("test")
    public Response getGreeting() {
        ObjectNode node = mapper().createObjectNode().put("hello", "world");
        return ok(node).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createIntent(InputStream stream) {
        try {

            double minLoad = 0;
            org.onosproject.net.Path path = null;

            ObjectNode jsonTree = (ObjectNode) mapper().readTree(stream);
            JsonNode DstHost = jsonTree.get("DstHost");
            JsonNode SrcHost = jsonTree.get("SrcHost");
            JsonNode MaxBw = jsonTree.get("Bandwidth");

            IpAddress oneIp = IpAddress.valueOf(SrcHost.asText());
            IpAddress twoIp = IpAddress.valueOf(DstHost.asText());
            HostService hostService = get(HostService.class);
            HostId oneId = hostService.getHostsByIp(oneIp).iterator().next().id();
            HostId twoId = hostService.getHostsByIp(twoIp).iterator().next().id();
            DeviceId DvcOneId = hostService.getHostsByIp(oneIp).iterator()
                    .next().location().deviceId();
            DeviceId DvcTwoId = hostService.getHostsByIp(twoIp).iterator()
                    .next().location().deviceId();
            TopologyService topologyService = get(TopologyService.class);
            CoreService coreService = get(CoreService.class);
            ApplicationId appId = coreService.registerApplication("onos.path.app");

            Set<org.onosproject.net.Path> p = topologyService.getPaths(
                    topologyService.currentTopology(), DvcOneId, DvcTwoId);


            for(org.onosproject.net.Path test: p) {

                //long maxLoad = 0;
                //long maxLoadB = 0;
                testReturn returnValues;

                returnValues = pathSelection(test.links(), MaxBw.asLong());
                //maxLoadB = pathSelection(test.backup().links(), MaxBw.asLong());


                if ((returnValues.maxLoad/1024) > MaxBw.asLong() &&
                        (returnValues.maxLoad/1024) > minLoad) {
                    if(returnValues.bwCondition!=true) {
                        minLoad = (returnValues.maxLoad/1024);
                        path = test;
                    }

                }else if((returnValues.maxLoad/1024) > minLoad &&
                        returnValues.bwCondition==true){
                    minLoad = (returnValues.maxLoad/1024);
                    path = test;
                }
                if(test.links().size() == 1){
                    path = test;
                }


            }

            backupPath(path.links(), oneId, twoId, appId);

            installFlow(path.src().deviceId(),path.src().port(),
                    hostService.getHost(oneId).location().port(),
                    oneId.mac(), twoId.mac(), appId);
            installFlow(path.src().deviceId(), hostService.getHost(oneId).location().port(),
                    path.src().port(),
                    twoId.mac(), oneId.mac(), appId);
            installFlow(path.dst().deviceId(), path.dst().port(),
                    hostService.getHost(twoId).location().port(),
                    twoId.mac(), oneId.mac(), appId);
            installFlow(path.dst().deviceId(), hostService.getHost(twoId).location().port(),
                    path.dst().port(),
                    oneId.mac(), twoId.mac(), appId);

            return Response.status(200).entity(MaxBw.toString()).build();

        } catch (IOException ioe) {
            throw new IllegalArgumentException(ioe);
        }
    }

    private long maxLoad(long a, long b){

        if(a<0){
            return b;
        }
        if(b<0){
            return a;
        }
        return  a > b ? a : b;
    }

    private testReturn pathSelection(List<Link> linksPath, long BW){

        long maxLoad = 0;
        long portSpeed = 0;
        long lportSpeed = 0;
        long loadSrcPort = 0;
        long loadDstPort = 0;
        boolean bwCondition = false;

        DeviceService deviceService=get(DeviceService.class);
        StatisticService service = get(StatisticService.class);

        for(Link links: linksPath){
            ConnectPoint cpSrc = new ConnectPoint(links.src().elementId(),
                    PortNumber.portNumber(links.src().port().toLong()));
            ConnectPoint cpDst = new ConnectPoint(links.dst().elementId(),
                    PortNumber.portNumber(links.dst().port().toLong()));
            Load srcPortLoad = service.load(cpSrc);
            Load dstPortLoad = service.load(cpDst);


            loadSrcPort = (((srcPortLoad.rate() * 8) / 1024)) * 2;
            loadDstPort = (((dstPortLoad.rate() * 8) / 1024)) * 2;
            lportSpeed = (deviceService.getPort(links.src().deviceId(),
                    links.src().port()).portSpeed())*1024;
            portSpeed = portSpeed + lportSpeed;

            maxLoad = maxLoad + maxLoad(loadSrcPort, loadDstPort);
            if(lportSpeed - maxLoad(loadSrcPort,loadDstPort) < (BW*1024)){
                bwCondition = true;
            }

        }
        maxLoad = ((portSpeed - maxLoad)/linksPath.size());
        if(bwCondition==true){
            return new testReturn(maxLoad, bwCondition);
        }else {
            return new testReturn(maxLoad, bwCondition);
        }

    }

    private void backupPath(List<Link> links, HostId oneId, HostId twoId, ApplicationId appId){

        PortNumber lastPort=links.get(0).dst().port();
        ElementId lastId=links.get(0).dst().elementId();

        for(Link l: links){
            if(lastId.equals(l.src().elementId())){
                installFlow(l.src().deviceId(), l.src().port(), lastPort,
                        oneId.mac(), twoId.mac(), appId);
                installFlow(l.src().deviceId(), lastPort, l.src().port(),
                        twoId.mac(), oneId.mac(), appId);
                lastId = l.dst().elementId();
                lastPort = l.dst().port();
            }


        }

    }

    private void installFlow(DeviceId deviceId, PortNumber outPort, PortNumber inPort,
                             MacAddress srcMac, MacAddress dstMac, ApplicationId appId){

        FlowRuleService flowService = get(FlowRuleService.class);

        TrafficTreatment treatment = DefaultTrafficTreatment.builder()
                .setOutput(outPort).build();

        TrafficSelector.Builder sbuilder;
        FlowRuleOperations.Builder rules = FlowRuleOperations.builder();

        sbuilder = DefaultTrafficSelector.builder();


        sbuilder.matchEthSrc(srcMac)
                .matchEthDst(dstMac).matchInPort(inPort);


        FlowRule addRule = DefaultFlowRule.builder()
                .forDevice(deviceId)
                .withSelector(sbuilder.build())
                .withTreatment(treatment)
                .withPriority(PRIORITY)
                .fromApp(appId)
                .makeTemporary(TIME_OUT)
                .build();

        rules.add(addRule);
        flowService.apply(rules.build());

    }

}

