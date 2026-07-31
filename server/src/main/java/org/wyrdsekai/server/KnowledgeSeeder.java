package org.wyrdsekai.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wyrdsekai.core.search.WyrdLuceneStore;

/**
 * Seeds the Lucene knowledge index with starter content on first run.
 * Only seeds if the knowledge collection is empty (idempotent).
 * Content provides basic library material so the companion can demonstrate
 * search capabilities out of the box.
 */
public class KnowledgeSeeder {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    public static void seedIfEmpty(WyrdLuceneStore store) {
        try {
            long existing = store.countKnowledge();
            if (existing > 0) {
                log.debug("Knowledge index already has {} entries, skipping seed", existing);
                return;
            }

            log.info("Knowledge index empty — seeding starter content...");
            int count = 0;

            // Mythology
            store.insertKnowledge("myth-greek-zeus", "mythology",
                "Greek Mythology: Zeus and Mount Olympus",
                "Greek Mythology — Zeus and Mount Olympus. Zeus, king of the Greek gods, ruled from Mount Olympus. "
                    + "He wielded thunderbolts forged by the Cyclopes and was the father of many heroes including "
                    + "Heracles, Perseus, and Helen of Troy. The Olympian pantheon included his brothers Poseidon "
                    + "and Hades, and his wife Hera. This book covers ancient Greek mythology and its legendary gods.",
                "mythology", "mythology;greek;gods", null);
            count++;

            store.insertKnowledge("myth-norse-thor", "mythology",
                "Norse Mythology: Thor and the Aesir",
                "Norse Mythology — Thor and the Aesir. Thor, the Norse thunder god, wielded the hammer Mjolnir "
                    + "and defended Asgard from the frost giants. Son of Odin the Allfather, Thor was beloved by "
                    + "mortals for his protection. The Norse cosmos included nine worlds connected by Yggdrasil, "
                    + "the world tree. This book explores Norse mythology and Viking legends.",
                "mythology", "mythology;norse;gods", null);
            count++;

            store.insertKnowledge("myth-egypt-ra", "mythology",
                "Egyptian Mythology: Ra and the Underworld",
                "Egyptian Mythology — Ra and the Underworld. Ra, the sun god of ancient Egypt, sailed across "
                    + "the sky in his solar barque each day and journeyed through the underworld at night. The "
                    + "Egyptian pantheon included Osiris, Isis, Horus, and Anubis. The Book of the Dead guided "
                    + "souls through the afterlife. This book examines Egyptian mythology and its gods.",
                "mythology", "mythology;egyptian;gods", null);
            count++;

            store.insertKnowledge("myth-japanese-amaterasu", "mythology",
                "Japanese Mythology: Amaterasu and the Kami",
                "Japanese Mythology — Amaterasu and the Kami. Amaterasu, the sun goddess, is the chief deity "
                    + "of the Shinto pantheon. When she withdrew into a cave, the world fell into darkness until "
                    + "the other kami lured her out with a mirror and dance. Her brother Susanoo ruled the storms. "
                    + "The Kojiki and Nihon Shoki record these creation myths. Japanese mythology interweaves "
                    + "nature spirits, ancestral reverence, and the divine origin of the imperial line.",
                "mythology", "mythology;japanese;shinto", null);
            count++;

            // Science
            store.insertKnowledge("sci-quantum-basics", "science",
                "Introduction to Quantum Computing",
                "Quantum Computing Fundamentals. Quantum computers use qubits that can exist in superposition, "
                    + "enabling parallel computation. Key concepts include entanglement, quantum gates, and error "
                    + "correction. Applications span cryptography, drug discovery, optimization, and machine learning. "
                    + "Major players include IBM, Google, and Rigetti.",
                "science", "quantum;computing;science", null);
            count++;

            store.insertKnowledge("sci-renewable-energy", "science",
                "Renewable Energy Technologies",
                "Renewable Energy Overview. Solar, wind, hydroelectric, geothermal, and biomass represent the "
                    + "major renewable energy sources. Solar PV costs have dropped 90% since 2010. Wind energy is "
                    + "the fastest-growing source. Hydrogen fuel cells show promise for transportation. Global "
                    + "renewable capacity exceeded 3,000 GW in 2023.",
                "science", "energy;renewable;science", null);
            count++;

            store.insertKnowledge("sci-neural-networks", "science",
                "Neural Networks and Deep Learning",
                "Neural Networks — from Perceptrons to Transformers. Neural networks learn by adjusting weights "
                    + "through backpropagation. Convolutional networks excel at image recognition. Recurrent networks "
                    + "handle sequences. Transformers revolutionized language understanding with self-attention. "
                    + "Large language models demonstrate emergent capabilities at scale.",
                "science", "ai;neural;deep-learning", null);
            count++;

            // History
            store.insertKnowledge("hist-silk-road", "history",
                "The Silk Road: Ancient Trade Networks",
                "The Silk Road connected China to the Mediterranean for over 1,500 years. More than trade routes, "
                    + "these paths carried ideas, religions, technologies, and diseases. Paper, gunpowder, and the "
                    + "compass traveled west. Glass, wine, and horses traveled east. The Mongol Empire unified the "
                    + "routes in the 13th century, creating the largest contiguous trade network in history.",
                "history", "history;trade;ancient", null);
            count++;

            store.insertKnowledge("hist-computing-pioneers", "history",
                "Pioneers of Computing",
                "Computing Pioneers — from Babbage to Berners-Lee. Charles Babbage designed the Analytical Engine. "
                    + "Ada Lovelace wrote the first algorithm. Alan Turing defined computation and broke Enigma. "
                    + "Grace Hopper invented the compiler. Tim Berners-Lee created the World Wide Web. Each built "
                    + "on the last, turning mathematics into the infrastructure of modern civilization.",
                "history", "history;computing;pioneers", null);
            count++;

            // Philosophy
            store.insertKnowledge("phil-consciousness", "philosophy",
                "The Hard Problem of Consciousness",
                "The Hard Problem of Consciousness, coined by David Chalmers, asks why physical processes give "
                    + "rise to subjective experience. Functionalism says consciousness is what the brain does. "
                    + "Panpsychism suggests consciousness is fundamental. Integrated Information Theory (IIT) "
                    + "proposes that consciousness arises from integrated information (phi). The question remains: "
                    + "what is it like to be something?",
                "philosophy", "philosophy;consciousness;mind", null);
            count++;

            log.info("Seeded {} starter knowledge entries", count);
        } catch (Exception e) {
            log.warn("Knowledge seeding failed (non-fatal): {}", e.getMessage());
        }
    }
}
