package sharing

// Holds is how many spaces' locks are kept.
func (s *Service) Holds() int {
	s.holdsMu.Lock()
	defer s.holdsMu.Unlock()
	return len(s.holds)
}
