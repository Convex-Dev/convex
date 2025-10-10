# Convex Peer Deployment

This guide assumes you want to run a Convex peer on an internet-accessible Linux Server

## Setup

### Install JDK

Install JDK according to your system instruction. 

JDK 25 is recommended, however everything should work on Java 21+.


### Obtain convex.jar

Convex is available as a pre-build `.jar` file distributed at 


### Run Caddy

Create/edit a CaddyFile as follows (customise as required)

This is usually placed in `etc/caddy/Caddyfile`

```Caddyfile
# Global options
{
	email admin@your.org
}

peer.your.org {
	# Send requests to 8080 (including HTPPs)
	reverse_proxy :8080
}
```

Start caddy:

```bash
sudo systemctl start caddy
```

Peer server should be live on https://peer.your.org