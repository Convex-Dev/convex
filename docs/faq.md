## Why should I use Convex?

That's up to you! But we hope you'll find it compelling for these sort of reasons:

- A fun and empowering experience as a developer
- The ability to easily build powerful decentralised applications
- Probably the best overall performance of any decentralised platform 

## What should go on-chain?

You often have a choice between putting code and information on the public Convex network or keeping it on a separate server when building a decentralised application. Some applications might do both: we call these "hybrid" dApps.

Some general principles:

- Put data *on-chain* when it need to be *publicly visible and verifiable*
- Put data *on-chain* when you need to make trusted transactions between parties (e.g. exchanging digital assets)
- Keep data *off-chain* when it needs to be private
- Keep code and data *off-chain* if there are significant compute or storage requirements (it would be too expensive to put on-chain)

## Is Convex fast?

Yes. But it's not just about transaction speed.

Convex is designed to offer a good overall combination of:

- Low latency to stable consensus (~1 second on global network)
- High execution throughput (10,000+ token-based smart contract transactions per second)
- Advanced features (smart contracts, memory accounting, an on-chain compiler, autonomous Actors)
- A unified global state machine
- True decentralisation as a public utility network (with delegated proof of stake) 
- Ability to operate a Peer with affordable hardware

We achieve all this *without* resorting to over-complicated scaling solutions that introduce various new problems (e.g. cross-shard transactions). We can always add additional scaling features later if we want to, but it may not even be necessary. 

## But XXX is faster! It can do YY million transactions per second!

Maybe. We're not really in the game of competing on the basis of meaningless benchmarks.

It's important to remember that performance isn't about a single number. It's about the being able to get what you want done quickly and efficiently, as a user of the system. 

We've seen plenty of big performance claims for decentralised platforms. The headlines may be impressive, but a lot of these don't really stack up when you examine more closely. Usually there are some significant compromises made to achieve these numbers. You can look out for: Unrealistic testing setups. Relaxed security requirements (e.g. using PoA networks). Networks that can't handle general purpose smart contracts. Issues with transactions that span across shards. Long time delays to confirm final consensus.

## Is Convex a Blockchain?

It depends on your definition. Convex shares many common attributes with traditional blockchains:

- A decentralised consensus network 
- Security from malicious actors with cryptographic techniques
- Decentralised ownership of accounts, including the ability to control digital assets and currencies
- Ability to deploy and execute secure smart contract code

Technically - it's not actually implemented as a blockchain (at least in the sense that there is linked list of blocks where each block contains the hash of a previous block). The Convex consensus algorithm creates an *ordering* of blocks, but the cryptographic hashes used to secure this ordering are kept outside the blocks themselves. This might seem like a minor detail but it gives us a big speed advantage, as blocks can be submitted and processed by the consensus network in parallel without having to know the hash of the preceding block.

So if we are talking about the general field of decentralised technology, it might be convenient to call Convex a blockchain. Just remember that it's a little different.

## How does Convex go so fast?

It's complex! But here are some of the most important points:

- The consensus algorithm is extremely fast. It can confirm blocks in a few milliseconds between peers running on a local network. The main latency delay in the global network is signal transmission over the Internet: the speed of light is a tricky problem.
- The CVM execution model is designed for performance: CVM operations are relatively high level, but are implemented using very efficient low level code. 
- We wrote a custom database (Etch) from scratch, specifically to support the performance needs of Convex. Having a database perfectly designed and tuned for the specific workload is a huge advantage.
- We exploit a lot of advanced features of the JVM, which is a very powerful platform backed by thousand man years of engineering effort. We especially appreciate the JIT compiler, concurrency, asynchronous IO and advanced memory management features. It probably wouldn't be feasible to build something as fast as Convex without these.
- Some of our team have been performance-oriented hackers for many years, with experience in game coding, distributed systems etc. We simply enjoy and take pride in writing fast, efficient code!

## Why does Convex use Lisp?

A variant of Lisp was chosen as the initial language for the CVM for a few reasons:

- Lisp expressions are essentially a direct encoding of the [Lambda Calculus](https://en.wikipedia.org/wiki/Lambda_calculus). This means that we are based on fundamentally sound computation theory.
- Lisp macros are a powerful tool for generating code, which is an ideal solution for building sophisticated smart contract capabilities. 
- Lisp is ideal as an expressive language for interactive development, with a long history of REPL-based usage. We felt this was ideal for a platform where we want developers to be instantly productive and able to interact directly with the system in real time.

Paul Graham's essay [Beating the Averages](http://www.paulgraham.com/avg.html) is an interesting perspective on the advantages of Lisp for building a business. Despite dating from 2001, we feel many of these points still stand today and are very relevant for people wanting to build applications using Convex.

## What is Memory Accounting?

Memory Accounting is the system in Convex used to track the usage of on-chain memory. Every time a user executes a transaction, the amount of memory used is calculated and deducted from the user's memory allowance. If the user has insufficient memory allowance, it is possible to automatically buy more on demand.

If a user executes a transaction that releases memory, the amount of released memory is credited back to the user's allowance. This creates a good incentive to "clean up after yourself". Actors and smart contracts should also be designed with the option to clean up memory after it is no longer required.

We need Memory Accounting because on-chain memory is a scarce resource, and needs to be reserved for the most valuable uses. An effective way of doing this is to make memory allowances themselves into a digital asset, that can be transferred and traded. This creates a market and an incentive to utilise memory as efficiently as possible.

## Is Convex Free?

Yes! Convex is free for anyone to use and build applications upon, and always will be. We are building Convex for everyone.

To make use of the resources of the network, small transaction fees are charged using the Convex native coin. This is necessary to compensate those who provide resources to the network (peer operators) and prevent denial of service attacks by people flooding the network with wasteful transactions. Our constant goal is to keep transaction fees small, so that it is never a significant issue for legitimate network users.

## What is the difference between Actors and Smart Contracts?

Actors are virtual agents that live their whole existence inside the Convex Virtual Machine. They are autonomous agents that can execute CVM code, manage digital assets, perform complex computation, make decisions. They follow strict rules that control their execution, so that they can be relied upon to behave in a particular way.

Smart Contracts are a concept: the idea of having real-world contracts or agreements that can be automatically executed and enforced by software, eliminating risk and the need to trust fallible humans.

You can use Convex Actors to implement smart contracts. Not every Actor needs to be a smart contract however: an Actor that simply stores on-chain information on your behalf isn't really a contract with anyone else.


## When will the main network go live?

As soon as it's ready. 

Longer answer: People will depend on Convex to be a secure, reliable platform for decentralised applications and digital assets. It is not acceptable to expose them to security risks from flaws in the platform, nor is it acceptable to make breaking changes to the CVM that could cause significant problems with smart contracts. We don't want to rush it, and we won't launch the main network until we are sure we've got it right, beyond reasonable doubt.

For latest updates, check out our Roadmap.


## Can I buy Convex as a cryptocurrency?

Not yet, but soon!

The Convex Foundation is a non-profit organisation which will facilitate the initial sale of Convex native coins in the near future. Funds raised will be re-invested in building Convex and the ecosystem.


## Who is building Convex?

We are a small team of dedicated hackers and creators passionate about building an amazing platform for the future digital economy.

We have chosen to stay anonymous for now. That may change. Or not. We might be Satoshi Nakamoto. Who knows?