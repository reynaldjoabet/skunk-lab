Each `dns_*.sh` shell script is a DNS provider plugin that automates the `DNS-01 challenge` for issuing SSL/TLS certificates via the ACME protocol (used by Let's Encrypt and other CAs).

When you request a certificate, the CA needs you to prove you control the domain. The `DNS-01` method does this by requiring you to create a specific `_acme-challenge` TXT record in your domain's DNS. Each script:
- `dns_add()` — Automatically creates the `_acme-challenge` TXT record via the DNS provider's API.
- `dns_rm()` — Removes the TXT record after validation is complete.

```sh
enable_public_net_ipv4: false
enable_public_net_ipv6: false
```    
nodes can only communicate over Hetzner's private network (10.0.0.0/16 in our config). That means:
- All nodes must be in the same Hetzner private network — which hetzner-k3s sets up automatically when private_network.enabled: true
- Cross-location still works — Hetzner private networks span locations within the same network zone (e.g., eu-central covers fsn1, nbg1, hel1). So your masters spread across those 3 locations will still communicate fine.
- It would NOT work across network zones — e.g., mixing fsn1 (EU) with ash (US) because Hetzner private networks don't span across zones. All nodes need to be in locations within the same network zone.
- Your management machine needs private network access too — since `use_private_ip: true` means `hetzner-k3s` SSHes via private IPs. You'd need either:
  - A bastion/jump host inside the same Hetzner private network
  - A VPN (e.g., WireGuard) into the private network
  - Or run `hetzner-k3s` from a Hetzner server in the same network

  ```sh
  Your laptop  →  (internet)  →  Bastion (public IP + private IP)  →  (private network)  →  Cluster nodes
  ```
 You SSH into the bastion first, then from there you can reach the private nodes. Or you configure an SSH jump in ~/.ssh/config:
```ssh
Host bastion
  HostName <bastion-public-ip>
  User root
  Port 2222

Host k8s-node-*
  ProxyJump bastion
  User root
  Port 2222
```

You install WireGuard on one server in the private network (or the bastion) and on your laptop. This creates an encrypted tunnel so your laptop acts as if it's inside the private network.
`Your laptop  →  (WireGuard tunnel over internet)  →  Private network  →  Cluster nodes`

Instead of running hetzner-k3s create from your laptop, you run it from a Hetzner server that's already in the same private network. That server can reach all private IPs directly — no tunnel or jump needed.

Your laptop is the orchestrator. It needs to reach every node to tell it what to do. That's why SSH access from your laptop to the nodes matters.

If hetzner-k3s ran on one of the Hetzner servers itself, then it would already be inside the private network and public IPs on the other nodes wouldn't matter. That was Option 3 from earlier

```sh
Step 1: Create cluster WITH public IPs (default)
        Your laptop ──SSH over internet──► nodes (public IPs)

Step 2: After cluster is running:
        - Set up bastion + WireGuard
        - Disable public IPs on nodes via Hetzner console
        - Future access goes through the tunnel
```

or

```sh
Step 1: Manually create a bastion server in Hetzner + private network
Step 2: Install WireGuard, connect your laptop
Step 3: Run hetzner-k3s create with use_private_ip: true and no public IPs
        Your laptop ──WireGuard──► bastion ──► nodes (private IPs only)
```        