{
  # = agent-server flake.lock nixpkgs (rev e7439b6)
  nixpkgs = {
    type = "github"; owner = "NixOS"; repo = "nixpkgs";
    rev = "e7439b6b14ad3cc35d05608ebca9bce01a25f5f8";
    narHash = "sha256-H83yz/do+hjOO0uujHyH8RgtIQP2v7Mw+cLwcZK/xKc=";
  };
  src.sha256 = "d0021f9bbcec0aae402c312377d8fa5d1ebc7ecb85b5b76ae9230ae06605a4de";
  modules2.sha256 = "c3c99fd9a47fdd6da1b74f02a59c921f54438af027cfbf98bdffbebf959212ec";
  gradleDist.sha256 = "511180a6556056502c597008e70c1fb93b2f5044e23c01a08ef493c3ee7cb53a";
  platform372.sha256 = "916410633df92a98da56aa7c87a1955f865f4d3b888373660794ef8173f72972";
  # HEAD 924d3b7e5 metadata normally read via git
  git = { count = "8175"; sha = "924d3b7e5"; time = "1792099659"; };
}
