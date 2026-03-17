# Fire Control System (FCS) — Modélisation et Vérification

**Système de Contrôle de Tir pour Véhicule Blindé**

> Modélisation formelle par réseaux de Pétri, vérification LTL, et implémentation distribuée Akka/Scala/Kafka

---

## Description

Ce projet implémente un **système de contrôle de tir (Fire Control System)** pour un véhicule blindé de type tank. Il combine :

- **Implémentation distribuée** en Scala avec Akka (modèle d'acteurs) et Apache Kafka (bus d'événements)
- **Modélisation formelle** par réseau de Pétri (13 places, 12 transitions)
- **Vérification formelle** : invariants métier (INV1-INV10) et propriétés LTL
- **Simulation comparée** entre le modèle formel et l'implémentation réelle

## Architecture

### Acteurs Akka

| Acteur | Rôle | Transition RdP |
|--------|------|----------------|
| `SensorActor` | Détection de cibles (radar/LIDAR) | T0 : P0 → P1 |
| `TrackingActor` | Verrouillage et calcul balistique | T1 : P1 → P2 |
| `AmmoActor` | Gestion du stock de munitions | T2 : P10 → P3 |
| `CommandActor` | Autorisation du commandant (ROE) | T3 : P2 → P4 |
| `FireControlActor` | Orchestrateur central du cycle de tir | T4, T5, T6, T7, T8 |
| `KafkaProducerActor` | Publication des événements sur Kafka | → P11 |
| `KafkaConsumerActor` | Audit trail et monitoring | T11 : P11 → P12 |
| `SupervisorActor` | Supervision et tolérance aux pannes | T9, T10 |

### Réseau de Pétri

```
M₀ = (1, 0, 0, 0, 0, 0, 0, 0, 0, 0, N, 0, 0)

Boucle principale :
  P0(Idle) → T0 → P1(Detected) → T1 → P2(Locked)
                                                  ↘
  P10(Stock) → T2 → P3(Loaded) ——→ T4(sync) → P5(Ready) → T5(fire) → P6(Firing)
                                                  ↗                         ↓
  P2 → T3 → P4(Authorized) ——→                              T6 → P7(Reload) + P8(Cooldown)
                                                                    ↓              ↓
                                                              T7 → P0        T8 → P0

Branche Kafka :  T0,T5 → P11(Queue) → T11 → P12(Logged)
Erreurs :        Px → T9 → P9(Error) → T10 → P0
```

### Topics Kafka

| Topic | Description |
|-------|-------------|
| `fcs.target.detected` | Détection de cible |
| `fcs.target.locked` | Verrouillage balistique |
| `fcs.fire.authorized` | Autorisation du commandant |
| `fcs.fire.executed` | Confirmation du tir |
| `fcs.ammo.status` | Statut du stock de munitions |
| `fcs.error.critical` | Erreurs critiques |
| `fcs.audit.log` | Audit trail consolidé |

## Verification Formelle

### Invariants métier (INV1-INV10)

| ID | Propriété | Type |
|----|-----------|------|
| INV1 | Pas de tir sans verrouillage | Sûreté |
| INV2 | Pas de tir sans munition chargée | Sûreté |
| INV3 | Pas de tir sans autorisation | Sûreté |
| INV4 | Stock munitions ≥ 0 | Sûreté |
| INV5 | Exclusion mutuelle tir/rechargement | Sûreté |
| INV6 | Pas de tir pendant le cooldown | Sûreté |
| INV7 | Conservation des jetons (flux de contrôle) | Structurel |
| INV8 | Tout tir est journalisé | Vivacité |
| INV9 | Retour à l'état initial | Vivacité |
| INV10 | Absence de deadlock | Vivacité |

### Propriétés LTL

| Formule | Signification |
|---------|---------------|
| `G(¬(firing ∧ reloading))` | Jamais de tir et rechargement simultanés |
| `G(fire → F(log_recorded))` | Tout tir finit par être journalisé |
| `G(error → F(idle))` | Récupération après erreur |
| `G(F(idle))` | Le système revient toujours à Idle |
| `G(¬(ammo < 0))` | Stock jamais négatif |
| `G(cooldown → ¬firing)` | Pas de tir pendant le cooldown |

## Utilisation

### Prérequis

- **JDK 21+**
- **sbt 1.10+**
- **Apache Kafka 3.x** (optionnel, pour le mode simulation complète)

### Build

```bash
sbt compile
```

### Tests

```bash
# Tous les tests
sbt test

# Tests de vérification formelle uniquement
sbt "testOnly fcs.petri.*"
```

### Exécution

```bash
# Vérification formelle (défaut)
sbt "run verify"

# Simulation Akka
sbt "run simulate"

# Simulation comparée Akka vs Réseau de Pétri
sbt "run compare"
```

## Structure du projet

```
fire-control-system/
├── build.sbt                          # Configuration sbt
├── .github/workflows/ci.yml           # GitHub Actions CI
├── src/
│   ├── main/scala/fcs/
│   │   ├── Main.scala                 # Point d'entrée
│   │   ├── model/
│   │   │   ├── Messages.scala         # Protocole de messages entre acteurs
│   │   │   └── FCSState.scala         # États et types du système
│   │   ├── actors/
│   │   │   ├── SensorActor.scala      # Détection de cibles
│   │   │   ├── TrackingActor.scala    # Verrouillage balistique
│   │   │   ├── AmmoActor.scala        # Gestion des munitions
│   │   │   ├── CommandActor.scala     # Autorisation de tir
│   │   │   ├── FireControlActor.scala # Orchestrateur central
│   │   │   ├── KafkaProducerActor.scala
│   │   │   ├── KafkaConsumerActor.scala
│   │   │   └── SupervisorActor.scala  # Supervision Akka
│   │   ├── kafka/
│   │   │   ├── Topics.scala           # Définition des topics Kafka
│   │   │   └── KafkaConfig.scala      # Configuration Kafka
│   │   └── petri/
│   │       ├── PetriNet.scala         # Modèle formel (Marking, Transition, PetriNet)
│   │       ├── FCSPetriNet.scala      # Définition concrète du RdP FCS
│   │       ├── StateSpaceAnalyzer.scala # Exploration BFS/DFS
│   │       ├── InvariantChecker.scala # Vérification INV1-INV10
│   │       └── LTLVerifier.scala      # Vérification LTL
│   ├── main/resources/
│   │   ├── application.conf           # Configuration Akka + Kafka
│   │   └── logback.xml                # Logging
│   └── test/scala/fcs/petri/
│       ├── PetriNetSpec.scala         # Tests unitaires du modèle
│       ├── FCSPetriNetSpec.scala      # Tests du réseau FCS
│       └── VerificationSpec.scala     # Tests de vérification
└── docs/
    └── fcs_petri_net.drawio           # Diagramme du réseau de Pétri
```

## Stack Technique

| Composant | Technologie |
|-----------|-------------|
| Langage | Scala 3.3 |
| Framework acteurs | Akka Typed 2.8 |
| Messaging | Apache Kafka 3.7 + Alpakka Kafka |
| Build | sbt 1.10 |
| Tests | ScalaTest + Akka TestKit |
| Analyseur RdP | Développé en Scala (pas d'outil externe) |
| CI/CD | GitHub Actions |
| Sérialisation | Circe (JSON) |

## Scenarios de test

1. **Nominal** — Cycle complet détection → tir → rechargement
2. **Tir sans autorisation** — Doit être refusé (ROE non validées)
3. **Stock épuisé** — Blocage correct du chargement
4. **Erreur en cours** — Transition vers Error_State et récupération
5. **Détections concurrentes** — Un seul cycle à la fois (exclusion mutuelle)
6. **Kafka offline** — La boucle de tir critique continue

## Licence

Projet académique — 2026
