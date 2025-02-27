# Classic Cluster Singleton

@@@ note

Akka Classic is the original Actor APIs, which have been improved by more type safe and guided Actor APIs, 
known as Akka Typed. Akka Classic is still fully supported and existing applications can continue to use 
the classic APIs. It is also possible to use Akka Typed together with classic actors within the same 
ActorSystem, see @ref[coexistense](typed/coexisting.md). For new projects we recommend using the new Actor APIs.

For the new API see @ref[cluster-singleton](typed/cluster-singleton.md).

@@@

## Dependency

To use Cluster Singleton, you must add the following dependency in your project:

@@dependency[sbt,Maven,Gradle] {
  group=com.typesafe.akka
  artifact=akka-cluster-tools_$scala.binary_version$
  version=$akka.version$
}

## Introduction

For some use cases it is convenient and sometimes also mandatory to ensure that
you have exactly one actor of a certain type running somewhere in the cluster.

Some examples:

 * single point of responsibility for certain cluster-wide consistent decisions, or
coordination of actions across the cluster system
 * single entry point to an external system
 * single master, many workers
 * centralized naming service, or routing logic

Using a singleton should not be the first design choice. It has several drawbacks,
such as single-point of bottleneck. Single-point of failure is also a relevant concern,
but for some cases this feature takes care of that by making sure that another singleton
instance will eventually be started.

The cluster singleton pattern is implemented by `akka.cluster.singleton.ClusterSingletonManager`.
It manages one singleton actor instance among all cluster nodes or a group of nodes tagged with
a specific role. `ClusterSingletonManager` is an actor that is supposed to be started as early as possible
on all nodes, or all nodes with specified role, in the cluster. The actual singleton actor is
started by the `ClusterSingletonManager` on the oldest node by creating a child actor from
supplied `Props`. `ClusterSingletonManager` makes sure that at most one singleton instance
is running at any point in time.

The singleton actor is always running on the oldest member with specified role.
The oldest member is determined by `akka.cluster.Member#isOlderThan`.
This can change when removing that member from the cluster. Be aware that there is a short time
period when there is no active singleton during the hand-over process.

The cluster failure detector will notice when oldest node becomes unreachable due to
things like JVM crash, hard shut down, or network failure. Then a new oldest node will
take over and a new singleton actor is created. For these failure scenarios there will
not be a graceful hand-over, but more than one active singletons is prevented by all
reasonable means. Some corner cases are eventually resolved by configurable timeouts.

You can access the singleton actor by using the provided `akka.cluster.singleton.ClusterSingletonProxy`,
which will route all messages to the current instance of the singleton. The proxy will keep track of
the oldest node in the cluster and resolve the singleton's `ActorRef` by explicitly sending the
singleton's `actorSelection` the `akka.actor.Identify` message and waiting for it to reply.
This is performed periodically if the singleton doesn't reply within a certain (configurable) time.
Given the implementation, there might be periods of time during which the `ActorRef` is unavailable,
e.g., when a node leaves the cluster. In these cases, the proxy will buffer the messages sent to the
singleton and then deliver them when the singleton is finally available. If the buffer is full
the `ClusterSingletonProxy` will drop old messages when new messages are sent via the proxy.
The size of the buffer is configurable and it can be disabled by using a buffer size of 0.

It's worth noting that messages can always be lost because of the distributed nature of these actors.
As always, additional logic should be implemented in the singleton (acknowledgement) and in the
client (retry) actors to ensure at-least-once message delivery.

The singleton instance will not run on members with status @ref:[WeaklyUp](cluster-usage.md#weakly-up).

## Potential problems to be aware of

This pattern may seem to be very tempting to use at first, but it has several drawbacks, some of them are listed below:

 * the cluster singleton may quickly become a *performance bottleneck*,
 * you can not rely on the cluster singleton to be *non-stop* available — e.g. when the node on which the singleton has
been running dies, it will take a few seconds for this to be noticed and the singleton be migrated to another node,
 * in the case of a *network partition* appearing in a Cluster that is using Automatic Downing  (see docs for
@ref:[Auto Downing](cluster-usage.md#automatic-vs-manual-downing)),
it may happen that the isolated clusters each decide to spin up their own singleton, meaning that there might be multiple
singletons running in the system, yet the Clusters have no way of finding out about them (because of the partition).

Especially the last point is something you should be aware of — in general when using the Cluster Singleton pattern
you should take care of downing nodes yourself and not rely on the timing based auto-down feature.

@@@ warning

**Don't use Cluster Singleton together with Automatic Downing**,
since it allows the cluster to split up into two separate clusters, which in turn will result
in *multiple Singletons* being started, one in each separate cluster!

@@@

## An Example

Assume that we need one single entry point to an external system. An actor that
receives messages from a JMS queue with the strict requirement that only one
JMS consumer must exist to make sure that the messages are processed in order.
That is perhaps not how one would like to design things, but a typical real-world
scenario when integrating with external systems.

Before explaining how to create a cluster singleton actor, let's define message classes @java[and their corresponding factory methods]
which will be used by the singleton.

Scala
:  @@snip [ClusterSingletonManagerSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/singleton/ClusterSingletonManagerSpec.scala) { #singleton-message-classes }

Java
:  @@snip [ClusterSingletonManagerTest.java](/akka-cluster-tools/src/test/java/akka/cluster/singleton/TestSingletonMessages.java) { #singleton-message-classes }

On each node in the cluster you need to start the `ClusterSingletonManager` and
supply the `Props` of the singleton actor, in this case the JMS queue consumer.

Scala
:  @@snip [ClusterSingletonManagerSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/singleton/ClusterSingletonManagerSpec.scala) { #create-singleton-manager }

Java
:  @@snip [ClusterSingletonManagerTest.java](/akka-cluster-tools/src/test/java/akka/cluster/singleton/ClusterSingletonManagerTest.java) { #create-singleton-manager }

Here we limit the singleton to nodes tagged with the `"worker"` role, but all nodes, independent of
role, can be used by not specifying `withRole`.

We use an application specific `terminationMessage` @java[(i.e. `TestSingletonMessages.end()` message)] to be able to close the
resources before actually stopping the singleton actor. Note that `PoisonPill` is a
perfectly fine `terminationMessage` if you only need to stop the actor.

Here is how the singleton actor handles the `terminationMessage` in this example.

Scala
:  @@snip [ClusterSingletonManagerSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/singleton/ClusterSingletonManagerSpec.scala) { #consumer-end }

Java
:  @@snip [ClusterSingletonManagerTest.java](/akka-cluster-tools/src/test/java/akka/cluster/singleton/Consumer.java) { #consumer-end }

With the names given above, access to the singleton can be obtained from any cluster node using a properly
configured proxy.

Scala
:  @@snip [ClusterSingletonManagerSpec.scala](/akka-cluster-tools/src/multi-jvm/scala/akka/cluster/singleton/ClusterSingletonManagerSpec.scala) { #create-singleton-proxy }

Java
:  @@snip [ClusterSingletonManagerTest.java](/akka-cluster-tools/src/test/java/akka/cluster/singleton/ClusterSingletonManagerTest.java) { #create-singleton-proxy }

A more comprehensive sample is available in the tutorial named 
@scala[[Distributed workers with Akka and Scala!](https://github.com/typesafehub/activator-akka-distributed-workers)]@java[[Distributed workers with Akka and Java!](https://github.com/typesafehub/activator-akka-distributed-workers-java)].

## Configuration

The following configuration properties are read by the `ClusterSingletonManagerSettings`
when created with a `ActorSystem` parameter. It is also possible to amend the `ClusterSingletonManagerSettings`
or create it from another config section with the same layout as below. `ClusterSingletonManagerSettings` is
a parameter to the `ClusterSingletonManager.props` factory method, i.e. each singleton can be configured
with different settings if needed.

@@snip [reference.conf](/akka-cluster-tools/src/main/resources/reference.conf) { #singleton-config }

The following configuration properties are read by the `ClusterSingletonProxySettings`
when created with a `ActorSystem` parameter. It is also possible to amend the `ClusterSingletonProxySettings`
or create it from another config section with the same layout as below. `ClusterSingletonProxySettings` is
a parameter to the `ClusterSingletonProxy.props` factory method, i.e. each singleton proxy can be configured
with different settings if needed.

@@snip [reference.conf](/akka-cluster-tools/src/main/resources/reference.conf) { #singleton-proxy-config }

## Supervision

There are two actors that could potentially be supervised. For the `consumer` singleton created above these would be: 

* Cluster singleton manager e.g. `/user/consumer` which runs on every node in the cluster
* The user actor e.g. `/user/consumer/singleton` which the manager starts on the oldest node

The Cluster singleton manager actor should not have its supervision strategy changed as it should always be running.
However it is sometimes useful to add supervision for the user actor. 
To accomplish this add a parent supervisor actor which will be used to create the 'real' singleton instance. 
Below is an example implementation (credit to [this StackOverflow answer](https://stackoverflow.com/a/36716708/779513))

Scala
:  @@snip [ClusterSingletonSupervision.scala](/akka-docs/src/test/scala/docs/cluster/singleton/ClusterSingletonSupervision.scala) { #singleton-supervisor-actor }

Java
:  @@snip [SupervisorActor.java](/akka-docs/src/test/java/jdocs/cluster/singleton/SupervisorActor.java) { #singleton-supervisor-actor }

And used here

Scala
:  @@snip [ClusterSingletonSupervision.scala](/akka-docs/src/test/scala/docs/cluster/singleton/ClusterSingletonSupervision.scala) { #singleton-supervisor-actor-usage }

Java
:  @@snip [ClusterSingletonSupervision.java](/akka-docs/src/test/java/jdocs/cluster/singleton/ClusterSingletonSupervision.java) { #singleton-supervisor-actor-usage-imports }
@@snip [ClusterSingletonSupervision.java](/akka-docs/src/test/java/jdocs/cluster/singleton/ClusterSingletonSupervision.java) { #singleton-supervisor-actor-usage }

## Lease

A @ref[lease](coordination.md) can be used as an additional safety measure to ensure that two singletons 
don't run at the same time. Reasons for how this can happen:

* Network partitions without an appropriate downing provider
* Mistakes in the deployment process leading to two separate Akka Clusters
* Timing issues between removing members from the Cluster on one side of a network partition and shutting them down on the other side

A lease can be a final backup that means that the singleton actor won't be created unless
the lease can be acquired. 

To use a lease for singleton set `akka.cluster.singleton.use-lease` to the configuration location
of the lease to use. A lease with with the name `<actor system name>-singleton-<singleton actor path>` is used and
the owner is set to the @scala[`Cluster(system).selfAddress.hostPort`]@java[`Cluster.get(system).selfAddress().hostPort()`].

If the cluster singleton manager can't acquire the lease it will keep retrying while it is the oldest node in the cluster.
If the lease is lost then the singleton actor will be terminated then the lease will be re-tried.

